#!/usr/bin/env node
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const digestPattern = /^sha256:[a-f0-9]{64}$/;
const manifestMediaTypes = new Set([
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
]);

export function verifyBuildxImageMetadata({ metadataJson, actionDigest, imageIdDigest }) {
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
  if (!digestPattern.test(manifestDigest ?? '') || !digestPattern.test(configDigest ?? '')) {
    throw new Error('Buildx output manifest or config digest is missing or malformed.');
  }
  if (!descriptor || descriptor.digest !== manifestDigest
    || descriptorConfigDigest !== configDigest || !manifestMediaTypes.has(mediaType)) {
    throw new Error('Buildx descriptor does not match its manifest and config metadata.');
  }
  if (!digestPattern.test(actionDigest ?? '') || actionDigest !== manifestDigest) {
    throw new Error('Buildx action digest output disagrees with exporter metadata.');
  }
  if (!digestPattern.test(imageIdDigest ?? '') || imageIdDigest !== configDigest) {
    throw new Error('Loaded Docker image ID does not match Buildx config digest.');
  }
  return { manifestDigest, configDigest, mediaType };
}
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try {
    const result = verifyBuildxImageMetadata({
      metadataJson: process.env.BUILD_METADATA_JSON ?? '',
      actionDigest: process.env.BUILD_DIGEST ?? '',
      imageIdDigest: process.env.IMAGE_ID_DIGEST ?? '',
    });
    process.stdout.write(`${result.manifestDigest}\n${result.configDigest}\n${result.mediaType}\n`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

\n