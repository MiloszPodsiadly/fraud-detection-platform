import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const [rawSbomPath, evidencePath, boundSbomPath] = process.argv.slice(2);
if (!rawSbomPath || !evidencePath || !boundSbomPath || process.argv.length !== 5) {
  throw new Error('Usage: node bind-image-sbom-identity.mjs <raw-sbom.json> <image-security-evidence.json> <bound-sbom.json>');
}

const sbom = JSON.parse(readFileSync(rawSbomPath, 'utf8'));
const evidence = JSON.parse(readFileSync(evidencePath, 'utf8'));
if (sbom.bomFormat !== 'CycloneDX' || !Array.isArray(sbom.components) || !sbom.metadata) {
  throw new Error('Image SBOM is not a complete CycloneDX document.');
}
if (!/^[a-f0-9]{40}$/.test(evidence.testedGitSha ?? '')) {
  throw new Error('Image evidence does not contain a tested Git SHA.');
}
if (evidence.schemaVersion !== 2
  || !/^sha256:[a-f0-9]{64}$/.test(evidence.imageIdDigest ?? '')
  || evidence.scannedImageDigest !== evidence.imageIdDigest
  || evidence.scannedImageIdentitySource !== 'docker-image-id'
  || typeof evidence.imageRef !== 'string'
  || typeof evidence.imagePlatform !== 'string'
  || !['docker-image-id', 'platform-config'].includes(evidence.dockerImageIdKind)
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
  || (evidence.sourceManifestMediaType !== null && typeof evidence.sourceManifestMediaType !== 'string')
  || (evidence.platformManifestMediaType !== null && typeof evidence.platformManifestMediaType !== 'string')) {
  throw new Error('Image evidence is missing a valid immutable image ID, reference, or platform.');
}

const manifestMediaTypes = new Set([
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
]);
if (evidence.dockerImageIdKind === 'docker-image-id') {
  if (evidence.buildxDigest === null
    || evidence.buildxDigest !== evidence.sourceImageDigest
    || evidence.buildxActionDigest !== evidence.buildxDigest
    || evidence.sourceManifestContentDigest !== evidence.buildxDigest
    || evidence.buildxDigest !== evidence.platformManifestDigest
    || evidence.platformManifestContentDigest !== evidence.platformManifestDigest
    || evidence.buildxConfigDigest !== evidence.imageIdDigest
    || evidence.platformConfigDigest !== evidence.imageIdDigest
    || evidence.sourceManifestMediaType !== evidence.platformManifestMediaType
    || !manifestMediaTypes.has(evidence.sourceManifestMediaType)) {
    throw new Error('Loaded Docker image ID is not bound to its Buildx output manifest and config metadata.');
  }
} else if (evidence.dockerImageIdKind === 'platform-config') {
  const pinnedDigest = evidence.imageRef.match(/@(?<digest>sha256:[a-f0-9]{64})$/)?.groups?.digest;
  if (!pinnedDigest
    || evidence.buildxDigest !== null
    || evidence.buildxActionDigest !== null
    || evidence.buildxConfigDigest !== null
    || evidence.sourceImageDigest !== pinnedDigest
    || evidence.sourceManifestContentDigest !== pinnedDigest
    || evidence.platformConfigDigest !== evidence.imageIdDigest
    || evidence.platformManifestContentDigest !== evidence.platformManifestDigest
    || !manifestMediaTypes.has(evidence.platformManifestMediaType)) {
    throw new Error('Loaded Docker image ID is not bound to the pinned registry platform config.');
  }
} else {
  throw new Error('Image evidence uses an unsupported identity kind.');
}

sbom.metadata.properties ??= [];
if (!Array.isArray(sbom.metadata.properties)) {
  throw new Error('CycloneDX metadata.properties is not an array.');
}
if (sbom.metadata.properties.some((property) => typeof property?.name === 'string'
  && property.name.startsWith('fraud-detection-platform:'))) {
  throw new Error('Raw image SBOM already contains derived identity properties.');
}

const bindings = new Map([
  ['fraud-detection-platform:image-ref', evidence.imageRef],
  ['fraud-detection-platform:image-id', evidence.imageIdDigest],
  ['fraud-detection-platform:scanned-digest', evidence.scannedImageDigest],
  ['fraud-detection-platform:scanner-input-image-id', evidence.imageIdDigest],
  ['fraud-detection-platform:image-platform', evidence.imagePlatform],
  ['fraud-detection-platform:tested-git-sha', evidence.testedGitSha],
  ['fraud-detection-platform:source-manifest-media-type', evidence.sourceManifestMediaType ?? 'not-applicable'],
  ['fraud-detection-platform:source-manifest-content-digest', evidence.sourceManifestContentDigest ?? 'not-applicable'],
]);
if (evidence.sourceImageDigest) {
  bindings.set('fraud-detection-platform:source-image-digest', evidence.sourceImageDigest);
}
if (evidence.platformManifestDigest) {
  bindings.set('fraud-detection-platform:platform-manifest-digest', evidence.platformManifestDigest);
  bindings.set('fraud-detection-platform:platform-manifest-content-digest', evidence.platformManifestContentDigest);
}
if (evidence.platformConfigDigest) {
  bindings.set('fraud-detection-platform:platform-config-digest', evidence.platformConfigDigest);
}

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
