#!/usr/bin/env node
import { execFileSync } from 'node:child_process';

const [imageRef, expectedPlatform, expectedImageId] = process.argv.slice(2);
if (!imageRef || !expectedPlatform || !expectedImageId || process.argv.length !== 5) {
  throw new Error('Usage: node inspect-pinned-image-platform.mjs <image@sha256:digest> <os/architecture> <docker-image-id>');
}
const referenceMatch = imageRef.match(/^(?<repository>.+)@(?<digest>sha256:[a-f0-9]{64})$/);
if (!referenceMatch) throw new Error(`Image reference is not immutable: ${imageRef}`);
if (!/^linux\/[a-z0-9_]+$/.test(expectedPlatform)) throw new Error(`Unsupported required platform: ${expectedPlatform}`);
if (!/^sha256:[a-f0-9]{64}$/.test(expectedImageId)) throw new Error(`Docker image ID is invalid: ${expectedImageId}`);

function inspectRaw(reference) {
  const output = execFileSync('docker', ['buildx', 'imagetools', 'inspect', '--raw', reference], { encoding: 'utf8' });
  let manifest;
  try {
    manifest = JSON.parse(output);
  } catch (error) {
    throw new Error(`Registry returned invalid manifest JSON for ${reference}: ${error.message}`);
  }
  if (!manifest || typeof manifest !== 'object' || typeof manifest.mediaType !== 'string') {
    throw new Error(`Registry manifest for ${reference} has no mediaType.`);
  }
  return manifest;
}

const sourceDigest = referenceMatch.groups.digest;
const sourceManifest = inspectRaw(imageRef);
const indexMediaTypes = new Set([
  'application/vnd.oci.image.index.v1+json',
  'application/vnd.docker.distribution.manifest.list.v2+json',
]);
const manifestMediaTypes = new Set([
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
]);

let platformManifestDigest;
let platformManifestMediaType;
let platformConfigDigest;
if (indexMediaTypes.has(sourceManifest.mediaType)) {
  if (!Array.isArray(sourceManifest.manifests)) throw new Error(`Image index ${imageRef} has no manifests array.`);
  const [expectedOs, expectedArchitecture] = expectedPlatform.split('/');
  const matchingDescriptors = sourceManifest.manifests.filter((descriptor) =>
    descriptor?.platform?.os === expectedOs && descriptor?.platform?.architecture === expectedArchitecture
    && typeof descriptor.digest === 'string' && /^sha256:[a-f0-9]{64}$/.test(descriptor.digest));
  if (matchingDescriptors.length !== 1) {
    throw new Error(`Image index ${imageRef} has ${matchingDescriptors.length} ${expectedPlatform} manifests; expected exactly one.`);
  }
  platformManifestDigest = matchingDescriptors[0].digest;
  const repository = referenceMatch.groups.repository;
  const platformManifest = inspectRaw(`${repository}@${platformManifestDigest}`);
  if (!manifestMediaTypes.has(platformManifest.mediaType)) {
    throw new Error(`Platform descriptor ${platformManifestDigest} did not resolve to an image manifest.`);
  }
  platformManifestMediaType = platformManifest.mediaType;
  platformConfigDigest = platformManifest.config?.digest;
} else if (manifestMediaTypes.has(sourceManifest.mediaType)) {
  platformManifestDigest = sourceDigest;
  platformManifestMediaType = sourceManifest.mediaType;
  platformConfigDigest = sourceManifest.config?.digest;
} else {
  throw new Error(`Unsupported registry image media type ${sourceManifest.mediaType}.`);
}

const dockerImageIdMatches = [
  ['platform-config', platformConfigDigest],
  ['platform-manifest', platformManifestDigest],
  ['source-manifest', sourceDigest],
].filter(([, digest]) => digest === expectedImageId);
if (dockerImageIdMatches.length === 0) {
  throw new Error(`Loaded Docker image ID ${expectedImageId} matches neither registry source manifest ${sourceDigest}, platform manifest ${String(platformManifestDigest)}, nor platform config ${String(platformConfigDigest)}.`);
}

process.stdout.write(`${JSON.stringify({
  sourceImageDigest: sourceDigest,
  sourceManifestMediaType: sourceManifest.mediaType,
  platformManifestDigest,
  platformManifestMediaType,
  platformConfigDigest,
  dockerImageIdKind: dockerImageIdMatches[0][0],
  expectedPlatform,
})}\n`);
