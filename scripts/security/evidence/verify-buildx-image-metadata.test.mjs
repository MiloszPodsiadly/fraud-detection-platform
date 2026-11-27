import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { verifyBuildxImageMetadata } from './verify-buildx-image-metadata.mjs';
import { createOciImageArchive } from './oci-image-archive.test-support.mjs';

const imageRef = 'fdp-local/test:revision';
const fixture = createOciImageArchive({ imageRef });
after(() => fixture.cleanup());

const { manifestDigest, configDigest, mediaType } = fixture;
const wrongDigest = `sha256:${'c'.repeat(64)}`;

function metadata() {
  return structuredClone(fixture.metadata);
}

function engineImage({ id = configDigest, descriptor = null, tags = [imageRef], platform = 'linux/amd64' } = {}) {
  const [Os, Architecture] = platform.split('/');
  return {
    Id: id,
    Os,
    Architecture,
    RepoTags: tags,
    RepoDigests: [],
    ...(descriptor ? { Descriptor: descriptor } : {}),
  };
}

function verify({ metadataValue = metadata(), digest = manifestDigest, image = engineImage(), ref = imageRef } = {}) {
  return verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(metadataValue),
    actionDigest: digest,
    imageInspectJson: JSON.stringify([image]),
    expectedPlatform: 'linux/amd64',
    expectedRef: ref,
    ociArchivePath: fixture.archivePath,
  });
}

test('verifies Buildx metadata against a classic Engine config ID and exact local tag', () => {
  assert.deepEqual(verify(), {
    manifestDigest,
    configDigest,
    mediaType,
    archiveSha256: fixture.archiveSha256,
    manifestContentDigest: manifestDigest,
    configContentDigest: configDigest,
    dockerEngineId: configDigest,
    dockerEngineIdKind: 'config-digest',
    dockerEngineConfigDigest: configDigest,
    dockerEngineDescriptorDigest: null,
    dockerEngineDescriptorMediaType: null,
    dockerEngineDescriptorConfigDigest: null,
    repoDigests: [],
    imagePlatform: 'linux/amd64',
  });
});

test('verifies a containerd Engine manifest ID only through its matching descriptor and config annotation', () => {
  const result = verify({
    image: engineImage({
      id: manifestDigest,
      descriptor: {
        digest: manifestDigest,
        mediaType,
        annotations: { 'config.digest': configDigest },
      },
    }),
  });
  assert.equal(result.dockerEngineId, manifestDigest);
  assert.equal(result.dockerEngineIdKind, 'manifest-descriptor');
  assert.equal(result.dockerEngineConfigDigest, configDigest);
});

test('rejects malformed Buildx metadata JSON', () => {
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: '{', actionDigest: manifestDigest, imageInspectJson: '[]', expectedPlatform: 'linux/amd64',
    expectedRef: imageRef, ociArchivePath: fixture.archivePath,
  }), /not valid JSON/);
});

test('rejects Buildx descriptor mismatch and missing digests', () => {
  const value = metadata();
  delete value['containerimage.descriptor'].annotations['config.digest'];
  value['containerimage.digest'] = wrongDigest;
  assert.throws(() => verify({ metadataValue: value }), /does not match its manifest/);
  const missing = metadata();
  delete missing['containerimage.config.digest'];
  assert.equal(verify({ metadataValue: missing }).configDigest, configDigest);
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(metadata()), actionDigest: manifestDigest,
    imageInspectJson: JSON.stringify([engineImage()]), expectedPlatform: 'linux/amd64', expectedRef: imageRef,
  }), /preserved OCI image archive/);
});

test('rejects disagreement between Buildx metadata and its action digest output', () => {
  assert.throws(() => verify({ digest: wrongDigest }), /action digest output disagrees/);
});

test('rejects an Engine ID unrelated to the verified manifest/config', () => {
  assert.throws(() => verify({ image: engineImage({ id: wrongDigest }) }), /not cryptographically bound/);
});

test('rejects a containerd descriptor with a forged config binding', () => {
  assert.throws(() => verify({
    image: engineImage({
      id: manifestDigest,
      descriptor: { digest: manifestDigest, mediaType, annotations: { 'config.digest': wrongDigest } },
    }),
  }), /descriptor config digest does not match/);
});

test('rejects an Engine descriptor for another manifest', () => {
  assert.throws(() => verify({
    image: engineImage({
      id: wrongDigest,
      descriptor: { digest: wrongDigest, mediaType, annotations: { 'config.digest': configDigest } },
    }),
  }), /descriptor does not match/);
});

test('rejects a Docker image inspect result with the wrong platform or multiple records', () => {
  assert.throws(() => verify({ image: engineImage({ platform: 'linux/arm64' }) }), /does not match linux\/amd64/);
  assert.throws(() => verifyBuildxImageMetadata({
    metadataJson: JSON.stringify(metadata()), actionDigest: manifestDigest,
    imageInspectJson: JSON.stringify([engineImage(), engineImage()]), expectedPlatform: 'linux/amd64',
    expectedRef: imageRef, ociArchivePath: fixture.archivePath,
  }), /exactly one inspected image/);
});

test('rejects a replaced image tag and a digest reference supplied where a local tag is required', () => {
  assert.throws(() => verify({ image: engineImage({ tags: ['fdp-local/replaced:revision'] }) }), /local image tag/);
  assert.throws(() => verify({ ref: `fdp-local/test@${manifestDigest}` }), /exact local tag/);
});

test('rejects unsupported Buildx manifest media types', () => {
  const value = metadata();
  value['containerimage.descriptor'].mediaType = 'application/vnd.oci.image.index.v1+json';
  assert.throws(() => verify({ metadataValue: value }), /does not match its manifest/);
});
