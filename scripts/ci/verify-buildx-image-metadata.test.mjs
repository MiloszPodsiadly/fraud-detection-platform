import assert from 'node:assert/strict';
import test from 'node:test';
import { verifyBuildxImageMetadata } from './verify-buildx-image-metadata.mjs';

const manifestDigest = `sha256:${'a'.repeat(64)}`;
const configDigest = `sha256:${'b'.repeat(64)}`;

function metadata() {
  return {
    'containerimage.digest': manifestDigest,
    'containerimage.config.digest': configDigest,
    'containerimage.descriptor': {
      digest: manifestDigest,
      mediaType: 'application/vnd.oci.image.manifest.v1+json',
      size: 506,
      annotations: { 'config.digest': configDigest },
    },
  };
}

test('verifies Buildx descriptor, action output, and loaded Docker config identity', () => {
  assert.deepEqual(verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(metadata()),
    actionDigest: manifestDigest,
    imageIdDigest: configDigest,
  }), {
    manifestDigest,
    configDigest,
    mediaType: 'application/vnd.oci.image.manifest.v1+json',
  });
});

test('rejects malformed metadata JSON', () => {
  assert.throws(() => verifyBuildxImageMetadata({ metadataJson: '{', actionDigest: manifestDigest, imageIdDigest: configDigest }), /not valid JSON/);
});

test('rejects a missing descriptor config digest', () => {
  const value = metadata();
  delete value['containerimage.descriptor'].annotations['config.digest'];
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(value),
    actionDigest: manifestDigest,
    imageIdDigest: configDigest,
  }), /does not match its manifest and config/);
});

test('rejects disagreement between Buildx metadata and its action digest output', () => {
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(metadata()),
    actionDigest: `sha256:${'c'.repeat(64)}`,
    imageIdDigest: configDigest,
  }), /action digest output disagrees/);
});

test('rejects a loaded Docker ID that does not match the Buildx config', () => {
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(metadata()),
    actionDigest: manifestDigest,
    imageIdDigest: `sha256:${'d'.repeat(64)}`,
  }), /loaded Docker image ID does not match/i);
});

test('rejects a Buildx descriptor with an unsupported media type', () => {
  const value = metadata();
  value['containerimage.descriptor'].mediaType = 'application/vnd.oci.image.index.v1+json';
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(value),
    actionDigest: manifestDigest,
    imageIdDigest: configDigest,
  }), /does not match its manifest and config/);
});
