import { SECURITY_POLICY } from '../config/policy.mjs';

const PROPERTY_PREFIX = SECURITY_POLICY.imageIdentity.sbomPropertyPrefix;

export function imageSbomIdentityBindings(evidence) {
  const bindings = new Map([
    ['image-ref', evidence.imageRef],
    ['image-id', evidence.imageIdDigest],
    ['docker-engine-id-kind', evidence.dockerImageIdKind],
    ['docker-engine-config-digest', evidence.dockerEngineConfigDigest],
    ['docker-engine-descriptor-digest', evidence.dockerEngineDescriptorDigest ?? 'not-applicable'],
    ['docker-engine-descriptor-config-digest', evidence.dockerEngineDescriptorConfigDigest ?? 'not-applicable'],
    ['scanned-digest', evidence.scannedImageDigest],
    ['scanner-input-image-id', evidence.imageIdDigest],
    ['image-platform', evidence.imagePlatform],
    ['tested-git-sha', evidence.testedGitSha],
    ['source-manifest-media-type', evidence.sourceManifestMediaType ?? 'not-applicable'],
    ['source-manifest-content-digest', evidence.sourceManifestContentDigest ?? 'not-applicable'],
  ].map(([name, value]) => [`${PROPERTY_PREFIX}${name}`, value]));
  if (evidence.sourceImageDigest) {
    bindings.set(`${PROPERTY_PREFIX}source-image-digest`, evidence.sourceImageDigest);
  }
  if (evidence.platformManifestDigest) {
    bindings.set(`${PROPERTY_PREFIX}platform-manifest-digest`, evidence.platformManifestDigest);
    bindings.set(`${PROPERTY_PREFIX}platform-manifest-content-digest`, evidence.platformManifestContentDigest);
  }
  if (evidence.platformConfigDigest) {
    bindings.set(`${PROPERTY_PREFIX}platform-config-digest`, evidence.platformConfigDigest);
  }
  if (evidence.buildxOciArchiveSha256) {
    bindings.set(`${PROPERTY_PREFIX}buildx-oci-archive-sha256`, evidence.buildxOciArchiveSha256);
  }
  return bindings;
}

export function isImageSbomIdentityProperty(name) {
  return typeof name === 'string' && name.startsWith(PROPERTY_PREFIX);
}
