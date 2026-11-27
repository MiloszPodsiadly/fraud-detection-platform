#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { IMAGE_INDEX_MEDIA_TYPES, IMAGE_MANIFEST_MEDIA_TYPES, parseDockerImageInspect, verifyImageEngineBinding } from './image-identity.mjs';

const [imageRef, expectedPlatform, dockerInspectPath] = process.argv.slice(2);
if (!imageRef || !expectedPlatform || !dockerInspectPath || process.argv.length !== 5) {
  throw new Error('Usage: node inspect-pinned-image-platform.mjs <image@sha256:digest> <os/architecture> <docker-inspect.json>');
}
const referenceMatch = imageRef.match(/^(?<repository>.+)@(?<digest>sha256:[a-f0-9]{64})$/);
if (!referenceMatch) throw new Error(`Image reference is not immutable: ${imageRef}`);
if (expectedPlatform !== SECURITY_POLICY.platforms.image) {
  throw new Error(`Expected platform must match central image policy ${SECURITY_POLICY.platforms.image}.`);
}

function inspectRaw(reference, expectedDigest) {
  const output = execFileSync('docker', ['buildx', 'imagetools', 'inspect', '--raw', reference]);
  const candidates = [output];
  if (output.at(-1) === 0x0a) candidates.push(output.subarray(0, -1));
  if (output.at(-1) === 0x0a && output.at(-2) === 0x0d) candidates.push(output.subarray(0, -2));
  const raw = candidates.find((candidate) => `sha256:${createHash('sha256').update(candidate).digest('hex')}` === expectedDigest);
  if (!raw) {
    const actual = `sha256:${createHash('sha256').update(output).digest('hex')}`;
    throw new Error(`Registry manifest bytes for ${reference} do not match ${expectedDigest} (received ${actual}).`);
  }
  let manifest;
  try {
    manifest = JSON.parse(raw.toString('utf8'));
  } catch (error) {
    throw new Error(`Registry returned invalid manifest JSON for ${reference}: ${error.message}`);
  }
  if (!manifest || typeof manifest !== 'object' || typeof manifest.mediaType !== 'string') {
    throw new Error(`Registry manifest for ${reference} has no mediaType.`);
  }
  return { manifest, contentDigest: expectedDigest, rawBase64: raw.toString('base64') };
}

const sourceDigest = referenceMatch.groups.digest;
const {
  manifest: sourceManifest,
  contentDigest: sourceManifestContentDigest,
  rawBase64: sourceManifestRawBase64,
} = inspectRaw(imageRef, sourceDigest);
const indexMediaTypes = IMAGE_INDEX_MEDIA_TYPES;
const manifestMediaTypes = IMAGE_MANIFEST_MEDIA_TYPES;

let platformManifestDigest;
let platformManifestMediaType;
let platformManifestContentDigest;
let platformManifestRawBase64;
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
  const {
    manifest: platformManifest,
    contentDigest,
    rawBase64,
  } = inspectRaw(`${repository}@${platformManifestDigest}`, platformManifestDigest);
  if (!manifestMediaTypes.has(platformManifest.mediaType)) {
    throw new Error(`Platform descriptor ${platformManifestDigest} did not resolve to an image manifest.`);
  }
  platformManifestContentDigest = contentDigest;
  platformManifestRawBase64 = rawBase64;
  platformManifestMediaType = platformManifest.mediaType;
  platformConfigDigest = platformManifest.config?.digest;
} else if (manifestMediaTypes.has(sourceManifest.mediaType)) {
  platformManifestDigest = sourceDigest;
  platformManifestContentDigest = sourceManifestContentDigest;
  platformManifestRawBase64 = sourceManifestRawBase64;
  platformManifestMediaType = sourceManifest.mediaType;
  platformConfigDigest = sourceManifest.config?.digest;
} else {
  throw new Error(`Unsupported registry image media type ${sourceManifest.mediaType}.`);
}

if (!/^sha256:[a-f0-9]{64}$/.test(platformConfigDigest ?? '')) {
  throw new Error(`Selected platform manifest ${platformManifestDigest} has no valid config digest.`);
}
const image = parseDockerImageInspect(readFileSync(dockerInspectPath, 'utf8'), expectedPlatform);
const engineBinding = verifyImageEngineBinding({
  image,
  expectedPlatform,
  expectedRef: imageRef,
  manifestDigest: platformManifestDigest,
  manifestMediaType: platformManifestMediaType,
  configDigest: platformConfigDigest,
  sourceDigest,
  sourceMediaType: sourceManifest.mediaType,
  acceptedRepoDigests: [sourceDigest, platformManifestDigest],
  requireRepoDigest: true,
});

process.stdout.write(`${JSON.stringify({
  repository: referenceMatch.groups.repository,
  sourceManifestRawBase64,
  sourceImageDigest: sourceDigest,
  sourceManifestMediaType: sourceManifest.mediaType,
  sourceManifestContentDigest,
  platformManifestRawBase64,
  platformManifestDigest,
  platformManifestMediaType,
  platformManifestContentDigest,
  platformConfigDigest,
  ...engineBinding,
  expectedPlatform,
})}\n`);
