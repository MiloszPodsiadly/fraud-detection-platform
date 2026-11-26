#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { IMAGE_MANIFEST_MEDIA_TYPES, parseDockerImageInspect, verifyImageEngineBinding } from './image-identity.mjs';

export function verifyBuildxImageMetadata({ metadataJson, actionDigest, imageInspectJson, expectedPlatform, expectedRef }) {
  let metadata;
  try {
    metadata = JSON.parse(metadataJson);
  } catch {
    throw new Error('Buildx metadata is not valid JSON.');
  }
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) {
    throw new Error('Buildx metadata must be an object.');
  }
  const manifestDigest = metadata['containerimage.digest'];
  const configDigest = metadata['containerimage.config.digest'];
  const descriptor = metadata['containerimage.descriptor'];
  const mediaType = descriptor?.mediaType;
  const descriptorConfigDigest = descriptor?.annotations?.['config.digest'];
  if (!/^sha256:[a-f0-9]{64}$/.test(manifestDigest ?? '')
    || !/^sha256:[a-f0-9]{64}$/.test(configDigest ?? '')) {
    throw new Error('Buildx output manifest or config digest is missing or malformed.');
  }
  if (!descriptor || descriptor.digest !== manifestDigest
    || !IMAGE_MANIFEST_MEDIA_TYPES.has(mediaType)
    || (descriptorConfigDigest !== undefined && descriptorConfigDigest !== configDigest)) {
    throw new Error('Buildx descriptor does not match its manifest and config metadata.');
  }
  if (!/^sha256:[a-f0-9]{64}$/.test(actionDigest ?? '') || actionDigest !== manifestDigest) {
    throw new Error('Buildx action digest output disagrees with exporter metadata.');
  }
  if (!expectedRef || expectedRef.includes('@')) {
    throw new Error('Buildx image must be inspected through the exact local tag used by the build.');
  }
  if (!expectedPlatform) throw new Error('Expected image platform is required.');
  const image = parseDockerImageInspect(imageInspectJson, expectedPlatform);
  const engineBinding = verifyImageEngineBinding({
    image,
    expectedPlatform,
    expectedRef,
    manifestDigest,
    manifestMediaType: mediaType,
    configDigest,
    acceptedRepoDigests: [manifestDigest],
  });
  return {
    manifestDigest,
    configDigest,
    mediaType,
    ...engineBinding,
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    const result = verifyBuildxImageMetadata({
      metadataJson: process.env.BUILD_METADATA_JSON ?? '',
      actionDigest: process.env.BUILD_DIGEST ?? '',
      imageInspectJson: readFileSync(process.env.IMAGE_INSPECT_PATH ?? '', 'utf8'),
      expectedPlatform: process.env.EXPECTED_PLATFORM ?? '',
      expectedRef: process.env.IMAGE_REF ?? '',
    });
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
