import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createOciImageArchive } from './oci-image-archive.test-support.mjs';
import { verifyOciImageArchive } from './oci-image-archive.mjs';

const imageRef = 'fdp-local/oci-proof:revision';
const fixture = createOciImageArchive({ imageRef, includeConfigDigestMetadata: false });
after(() => fixture.cleanup());

test('derives platform manifest and config digests from verified OCI archive bytes when optional Buildx config metadata is absent', () => {
  const identity = verifyOciImageArchive({
    archivePath: fixture.archivePath,
    expectedRef: imageRef,
    expectedPlatform: 'linux/amd64',
    metadata: fixture.metadata,
    actionDigest: fixture.manifestDigest,
  });

  assert.equal(identity.manifestDigest, fixture.manifestDigest);
  assert.equal(identity.configDigest, fixture.configDigest);
  assert.equal(identity.archiveSha256, fixture.archiveSha256);
});

test('rejects an OCI image archive annotated for another image reference', () => {
  assert.throws(() => verifyOciImageArchive({
    archivePath: fixture.archivePath,
    expectedRef: 'fdp-local/other:revision',
    expectedPlatform: 'linux/amd64',
    metadata: fixture.metadata,
    actionDigest: fixture.manifestDigest,
  }), /unambiguous linux\/amd64 descriptors/);
});

test('rejects Buildx metadata whose reported config digest disagrees with the OCI config bytes', () => {
  const metadata = {
    ...fixture.metadata,
    'containerimage.config.digest': `sha256:${'c'.repeat(64)}`,
  };
  assert.throws(() => verifyOciImageArchive({
    archivePath: fixture.archivePath,
    expectedRef: imageRef,
    expectedPlatform: 'linux/amd64',
    metadata,
    actionDigest: fixture.manifestDigest,
  }), /disagrees with the content-addressed OCI archive/);
});

test('rejects an image archive whose selected config platform does not match the requested platform', () => {
  const armFixture = createOciImageArchive({ imageRef, platform: 'linux/arm64' });
  try {
    assert.throws(() => verifyOciImageArchive({
      archivePath: armFixture.archivePath,
      expectedRef: imageRef,
      expectedPlatform: 'linux/amd64',
      metadata: armFixture.metadata,
      actionDigest: armFixture.manifestDigest,
    }), /unambiguous linux\/amd64 descriptors/);
  } finally {
    armFixture.cleanup();
  }
});
