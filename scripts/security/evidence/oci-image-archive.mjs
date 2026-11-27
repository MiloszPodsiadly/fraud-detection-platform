import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { closeSync, lstatSync, openSync, readSync } from 'node:fs';
import { IMAGE_MANIFEST_MEDIA_TYPES, isSha256Digest } from './image-identity.mjs';

const OCI_REF_NAME = 'org.opencontainers.image.ref.name';
const OCI_CONFIG_MEDIA_TYPE = 'application/vnd.oci.image.config.v1+json';
const DOCKER_CONFIG_MEDIA_TYPE = 'application/vnd.docker.container.image.v1+json';
const MAX_INDEX_BYTES = 8 * 1024 * 1024;
const MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
const MAX_CONFIG_BYTES = 64 * 1024 * 1024;

export function verifyOciImageArchive({ archivePath, expectedRef, expectedPlatform, metadata, actionDigest }) {
  if (!archivePath || !expectedRef || !expectedPlatform) {
    throw new Error('OCI archive path, exact image reference, and expected platform are required.');
  }
  const archiveStat = lstatSync(archivePath);
  if (!archiveStat.isFile() || !Number.isSafeInteger(archiveStat.size) || archiveStat.size <= 0) {
    throw new Error('OCI image archive is empty or has an invalid size.');
  }
  const archiveSha256 = sha256File(archivePath);
  const layout = readArchiveJson(archivePath, 'oci-layout', 1024, 'OCI layout descriptor');
  if (layout?.imageLayoutVersion !== '1.0.0') {
    throw new Error('OCI archive does not declare the supported image layout version 1.0.0.');
  }
  const index = readArchiveJson(archivePath, 'index.json', MAX_INDEX_BYTES, 'OCI image index');
  if (index?.schemaVersion !== 2 || !Array.isArray(index.manifests) || index.manifests.length === 0) {
    throw new Error('OCI image archive index is missing its manifest descriptors.');
  }
  if (index.mediaType !== undefined && index.mediaType !== 'application/vnd.oci.image.index.v1+json') {
    throw new Error('OCI archive index declares an unsupported media type.');
  }
  const [expectedOs, expectedArchitecture] = expectedPlatform.split('/');
  if (!expectedOs || !expectedArchitecture || expectedPlatform.split('/').length !== 2) {
    throw new Error('Expected image platform must use os/architecture form.');
  }
  const selected = index.manifests.filter((descriptor) => {
    const reference = descriptor?.annotations?.[OCI_REF_NAME];
    const platform = descriptor?.platform;
    const platformMatches = platform
      ? platform.os === expectedOs && platform.architecture === expectedArchitecture
      : index.manifests.length === 1;
    return platformMatches && (reference === undefined || reference === expectedRef);
  });
  if (selected.length !== 1) {
    throw new Error(`OCI archive has ${selected.length} unambiguous ${expectedPlatform} descriptors for the requested image.`);
  }
  const descriptor = selected[0];
  if (descriptor.annotations?.[OCI_REF_NAME] !== undefined
    && descriptor.annotations[OCI_REF_NAME] !== expectedRef) {
    throw new Error('OCI archive image reference annotation does not match the requested tag.');
  }
  if (!isSha256Digest(descriptor.digest) || !IMAGE_MANIFEST_MEDIA_TYPES.has(descriptor.mediaType)
    || !Number.isSafeInteger(descriptor.size) || descriptor.size < 0) {
    throw new Error('OCI archive descriptor is not a supported content-addressed image manifest.');
  }
  const manifestBytes = readArchiveBlob(archivePath, descriptor.digest, descriptor.size, MAX_MANIFEST_BYTES, 'manifest');
  const manifest = parseJson(manifestBytes, 'OCI platform manifest');
  if (manifest.schemaVersion !== 2 || manifest.mediaType !== descriptor.mediaType
    || !manifest.config || !isSha256Digest(manifest.config.digest)
    || ![OCI_CONFIG_MEDIA_TYPE, DOCKER_CONFIG_MEDIA_TYPE].includes(manifest.config.mediaType)
    || !Number.isSafeInteger(manifest.config.size) || manifest.config.size < 0) {
    throw new Error('OCI platform manifest does not match its descriptor or has no valid config descriptor.');
  }
  const configBytes = readArchiveBlob(archivePath, manifest.config.digest, manifest.config.size, MAX_CONFIG_BYTES, 'image config');
  const config = parseJson(configBytes, 'OCI image config');
  if (config.os !== expectedOs || config.architecture !== expectedArchitecture) {
    throw new Error(`OCI image config platform ${config.os}/${config.architecture} does not match ${expectedPlatform}.`);
  }
  if (metadata && typeof metadata === 'object') {
    const metadataDescriptor = metadata['containerimage.descriptor'];
    if (metadata['containerimage.digest'] !== descriptor.digest
      || metadataDescriptor?.digest !== descriptor.digest
      || metadataDescriptor?.mediaType !== descriptor.mediaType
      || (metadataDescriptor?.size !== undefined && metadataDescriptor.size !== manifestBytes.length)
      || (metadata['containerimage.config.digest'] !== undefined
        && metadata['containerimage.config.digest'] !== manifest.config.digest)
      || (metadataDescriptor?.annotations?.['config.digest'] !== undefined
        && metadataDescriptor.annotations['config.digest'] !== manifest.config.digest)) {
      throw new Error('Buildx metadata disagrees with the content-addressed OCI archive manifest or config.');
    }
  }
  if (sha256File(archivePath) !== archiveSha256) {
    throw new Error('OCI image archive changed while its content-addressed identity was being verified.');
  }
  if (!isSha256Digest(actionDigest) || actionDigest !== descriptor.digest) {
    throw new Error('Buildx action digest output does not match the hashed OCI platform manifest.');
  }
  return {
    manifestDigest: descriptor.digest,
    configDigest: manifest.config.digest,
    mediaType: descriptor.mediaType,
    archiveSha256,
    manifestContentDigest: descriptor.digest,
    configContentDigest: manifest.config.digest,
    expectedPlatform,
  };
}

function readArchiveJson(archivePath, entryName, maxBytes, description) {
  const bytes = readArchiveEntry(archivePath, entryName, maxBytes);
  return parseJson(bytes, description);
}

function readArchiveBlob(archivePath, digest, expectedSize, maxBytes, description) {
  const member = `blobs/sha256/${digest.slice('sha256:'.length)}`;
  const bytes = readArchiveEntry(archivePath, member, maxBytes);
  const actualDigest = `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
  if (actualDigest !== digest || (expectedSize !== undefined && expectedSize !== bytes.length)) {
    throw new Error(`OCI ${description} bytes do not match their descriptor digest or size.`);
  }
  return bytes;
}

function readArchiveEntry(archivePath, member, maxBytes) {
  try {
    return execFileSync('tar', ['-xOf', archivePath, member], { maxBuffer: maxBytes + 1 });
  } catch (error) {
    throw new Error(`Unable to read ${member} from the OCI image archive: ${error.message}`);
  }
}

function parseJson(bytes, description) {
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch {
    throw new Error(`${description} is not valid JSON.`);
  }
}

function sha256File(filePath) {
  const fd = openSync(filePath, 'r');
  const hash = createHash('sha256');
  const buffer = Buffer.allocUnsafe(1024 * 1024);
  try {
    let bytesRead;
    do {
      bytesRead = readSync(fd, buffer, 0, buffer.length, null);
      if (bytesRead > 0) hash.update(buffer.subarray(0, bytesRead));
    } while (bytesRead > 0);
  } finally {
    closeSync(fd);
  }
  return hash.digest('hex');
}
