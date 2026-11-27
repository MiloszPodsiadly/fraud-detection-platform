import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { IMAGE_INDEX_MEDIA_TYPES, IMAGE_MANIFEST_MEDIA_TYPES } from './image-identity.mjs';
import { imageSbomIdentityBindings, isImageSbomIdentityProperty } from './image-sbom-identity.mjs';

const [rawSbomPath, evidencePath, boundSbomPath] = process.argv.slice(2);
if (!rawSbomPath || !evidencePath || !boundSbomPath || process.argv.length !== 5) {
  throw new Error('Usage: node bind-image-sbom-identity.mjs <raw-sbom.json> <image-security-evidence.json> <bound-sbom.json>');
}

const sbom = JSON.parse(readFileSync(rawSbomPath, 'utf8'));
const evidence = JSON.parse(readFileSync(evidencePath, 'utf8'));
if (sbom.bomFormat !== SECURITY_POLICY.formats.imageSbomBomFormat || !Array.isArray(sbom.components) || !sbom.metadata) {
  throw new Error('Image SBOM is not a complete CycloneDX document.');
}
if (!/^[a-f0-9]{40}$/.test(evidence.testedGitSha ?? '')) {
  throw new Error('Image evidence does not contain a tested Git SHA.');
}
if (evidence.schemaVersion !== SECURITY_POLICY.imageIdentity.evidenceSchemaVersion
  || !/^sha256:[a-f0-9]{64}$/.test(evidence.imageIdDigest ?? '')
  || evidence.scannedImageDigest !== evidence.imageIdDigest
  || evidence.scannedImageIdentitySource !== 'docker-engine-image-id'
  || evidence.dockerEngineId !== evidence.imageIdDigest
  || !/^sha256:[a-f0-9]{64}$/.test(evidence.dockerEngineConfigDigest ?? '')
  || typeof evidence.imageRef !== 'string'
  || typeof evidence.imagePlatform !== 'string'
  || !SECURITY_POLICY.imageIdentity.dockerImageIdKinds.includes(evidence.dockerImageIdKind)
  || evidence.platformConfigDigest !== evidence.dockerEngineConfigDigest
  || (evidence.platformConfigDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.platformConfigDigest ?? ''))
  || (evidence.platformManifestDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.platformManifestDigest ?? ''))
  || (evidence.sourceImageDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.sourceImageDigest ?? ''))
  || (evidence.sourceManifestContentDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.sourceManifestContentDigest ?? ''))
  || (evidence.platformManifestContentDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.platformManifestContentDigest ?? ''))
  || (evidence.buildxOciArchiveSha256 !== null
    && !/^[a-f0-9]{64}$/.test(evidence.buildxOciArchiveSha256 ?? ''))
  || (evidence.sourceManifestMediaType !== null && typeof evidence.sourceManifestMediaType !== 'string')
  || (evidence.platformManifestMediaType !== null && typeof evidence.platformManifestMediaType !== 'string')) {
  throw new Error('Image evidence is missing a valid immutable image ID, reference, or platform.');
}

if (evidence.dockerImageIdKind === 'config-digest') {
  if (evidence.imageIdDigest !== evidence.platformConfigDigest) {
    throw new Error('Classic Docker Engine ID does not match the verified platform config digest.');
  }
} else if (evidence.dockerImageIdKind === 'manifest-descriptor') {
  if (evidence.imageIdDigest !== evidence.platformManifestDigest
    || evidence.dockerEngineDescriptorDigest !== evidence.platformManifestDigest
    || evidence.dockerEngineDescriptorMediaType !== evidence.platformManifestMediaType
    || (evidence.dockerEngineDescriptorConfigDigest !== null
      && evidence.dockerEngineDescriptorConfigDigest !== evidence.platformConfigDigest)) {
    throw new Error('Containerd Docker Engine manifest ID is not bound to the verified platform config descriptor.');
  }
} else if (evidence.dockerImageIdKind === 'source-index-descriptor') {
  if (evidence.imageIdDigest !== evidence.sourceImageDigest
    || evidence.dockerEngineDescriptorDigest !== evidence.sourceImageDigest
    || evidence.dockerEngineDescriptorMediaType !== evidence.sourceManifestMediaType
    || !IMAGE_INDEX_MEDIA_TYPES.has(evidence.sourceManifestMediaType)) {
    throw new Error('Containerd Docker Engine index ID is not bound to the verified source image descriptor.');
  }
}
if (evidence.buildxDigest !== null) {
  if (evidence.buildxDigest === null
    || evidence.buildxDigest !== evidence.sourceImageDigest
    || evidence.buildxActionDigest !== evidence.buildxDigest
    || evidence.sourceManifestContentDigest !== evidence.buildxDigest
    || evidence.buildxDigest !== evidence.platformManifestDigest
    || evidence.platformManifestContentDigest !== evidence.platformManifestDigest
    || evidence.buildxConfigDigest !== evidence.platformConfigDigest
    || !/^[a-f0-9]{64}$/.test(evidence.buildxOciArchiveSha256 ?? '')
    || evidence.sourceManifestMediaType !== evidence.platformManifestMediaType
    || !IMAGE_MANIFEST_MEDIA_TYPES.has(evidence.sourceManifestMediaType)) {
    throw new Error('Loaded Docker image ID is not bound to its Buildx output manifest and config metadata.');
  }
} else {
  const pinnedDigest = evidence.imageRef.match(/@(?<digest>sha256:[a-f0-9]{64})$/)?.groups?.digest;
  if (!pinnedDigest
    || evidence.buildxDigest !== null
    || evidence.buildxActionDigest !== null
    || evidence.buildxConfigDigest !== null
    || evidence.buildxOciArchiveSha256 !== null
    || evidence.sourceImageDigest !== pinnedDigest
    || evidence.sourceManifestContentDigest !== pinnedDigest
    || evidence.platformManifestContentDigest !== evidence.platformManifestDigest
    || !IMAGE_MANIFEST_MEDIA_TYPES.has(evidence.platformManifestMediaType)) {
    throw new Error('Loaded Docker image ID is not bound to the pinned registry platform config.');
  }
}

sbom.metadata.properties ??= [];
if (!Array.isArray(sbom.metadata.properties)) {
  throw new Error('CycloneDX metadata.properties is not an array.');
}
if (sbom.metadata.properties.some((property) => isImageSbomIdentityProperty(property?.name))) {
  throw new Error('Raw image SBOM already contains derived identity properties.');
}

const bindings = imageSbomIdentityBindings(evidence);

for (const [name, value] of bindings) {
  const existing = sbom.metadata.properties.filter((property) => property?.name === name);
  if (existing.length > 1) {
    throw new Error(`Image SBOM already contains duplicate identity property ${name}.`);
  }
  if (existing.length === 1 && existing[0].value !== value) {
    throw new Error(`Raw image SBOM contradicts independently captured identity property ${name}.`);
  }
  if (existing.length === 0) sbom.metadata.properties.push({ name, value });
}

writeFileSync(resolve(boundSbomPath), `${JSON.stringify(sbom, null, 2)}\n`);
