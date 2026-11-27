import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const OCI_CONFIG_MEDIA_TYPE = 'application/vnd.oci.image.config.v1+json';
const OCI_MANIFEST_MEDIA_TYPE = 'application/vnd.oci.image.manifest.v1+json';

export function createOciImageArchive({ imageRef, platform = 'linux/amd64', includeConfigDigestMetadata = true }) {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'fdp oci archive fixture '));
  const archivePath = path.join(directory, 'image.oci.tar');
  const [osName, architecture] = platform.split('/');
  const configBytes = Buffer.from(JSON.stringify({ os: osName, architecture, config: {} }));
  const configDigest = digest(configBytes);
  const manifest = {
    schemaVersion: 2,
    mediaType: OCI_MANIFEST_MEDIA_TYPE,
    config: {
      mediaType: OCI_CONFIG_MEDIA_TYPE,
      digest: configDigest,
      size: configBytes.length,
    },
    layers: [],
  };
  const manifestBytes = Buffer.from(JSON.stringify(manifest));
  const manifestDigest = digest(manifestBytes);
  const index = {
    schemaVersion: 2,
    mediaType: 'application/vnd.oci.image.index.v1+json',
    manifests: [{
      mediaType: OCI_MANIFEST_MEDIA_TYPE,
      digest: manifestDigest,
      size: manifestBytes.length,
      platform: { os: osName, architecture },
      annotations: { 'org.opencontainers.image.ref.name': imageRef },
    }],
  };
  writeFileSync(path.join(directory, 'oci-layout'), '{"imageLayoutVersion":"1.0.0"}\n');
  writeFileSync(path.join(directory, 'index.json'), `${JSON.stringify(index)}\n`);
  writeBlob(directory, configDigest, configBytes);
  writeBlob(directory, manifestDigest, manifestBytes);
  execFileSync('tar', ['-cf', archivePath, 'oci-layout', 'index.json', 'blobs/sha256'], { cwd: directory });

  const metadata = {
    'containerimage.digest': manifestDigest,
    ...(includeConfigDigestMetadata ? { 'containerimage.config.digest': configDigest } : {}),
    'containerimage.descriptor': {
      digest: manifestDigest,
      mediaType: OCI_MANIFEST_MEDIA_TYPE,
      size: manifestBytes.length,
      annotations: { 'config.digest': configDigest },
    },
  };

  return {
    archivePath,
    manifestDigest,
    configDigest,
    mediaType: OCI_MANIFEST_MEDIA_TYPE,
    metadata,
    archiveSha256: createHash('sha256').update(readFileSync(archivePath)).digest('hex'),
    cleanup: () => rmSync(directory, { recursive: true, force: true }),
  };
}

function digest(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

function writeBlob(directory, digestValue, bytes) {
  const blobPath = path.join(directory, 'blobs', 'sha256', digestValue.slice('sha256:'.length));
  mkdirSync(path.dirname(blobPath), { recursive: true });
  writeFileSync(blobPath, bytes);
}
