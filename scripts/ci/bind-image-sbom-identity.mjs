import { readFileSync, writeFileSync } from 'node:fs';

const [sbomPath, evidencePath] = process.argv.slice(2);
if (!sbomPath || !evidencePath || process.argv.length !== 4) {
  throw new Error('Usage: node bind-image-sbom-identity.mjs <sbom.json> <image-security-evidence.json>');
}

const sbom = JSON.parse(readFileSync(sbomPath, 'utf8'));
const evidence = JSON.parse(readFileSync(evidencePath, 'utf8'));
if (sbom.bomFormat !== 'CycloneDX' || !Array.isArray(sbom.components) || !sbom.metadata) {
  throw new Error('Image SBOM is not a complete CycloneDX document.');
}
if (!/^[a-f0-9]{40}$/.test(evidence.testedGitSha ?? '')) {
  throw new Error('Image evidence does not contain a tested Git SHA.');
}
if (!/^sha256:[a-f0-9]{64}$/.test(evidence.imageIdDigest ?? '')
  || !/^sha256:[a-f0-9]{64}$/.test(evidence.scannedImageDigest ?? '')
  || typeof evidence.imageRef !== 'string'
  || typeof evidence.imagePlatform !== 'string'
  || typeof evidence.sourceManifestMediaType !== 'string'
  || !evidence.sourceManifestMediaType
  || typeof evidence.platformManifestMediaType !== 'string'
  || !evidence.platformManifestMediaType
  || evidence.sourceImageDigest !== evidence.scannedImageDigest
  || typeof evidence.dockerImageIdKind !== 'string'
  || (evidence.platformConfigDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.platformConfigDigest ?? ''))
  || (evidence.platformManifestDigest !== null
    && !/^sha256:[a-f0-9]{64}$/.test(evidence.platformManifestDigest ?? ''))) {
  throw new Error('Image evidence is missing a valid image reference, digest, or platform.');
}

const imageIdIsBoundToImageSource = evidence.dockerImageIdKind === 'docker-image-id'
  ? evidence.sourceManifestMediaType === 'local-image-config'
    && evidence.platformManifestMediaType === 'local-image-config'
    && evidence.sourceImageDigest === evidence.imageIdDigest
  : evidence.dockerImageIdKind === 'platform-config'
    ? evidence.platformConfigDigest === evidence.imageIdDigest
    : evidence.dockerImageIdKind === 'platform-manifest'
      ? evidence.platformManifestDigest === evidence.imageIdDigest
      : evidence.dockerImageIdKind === 'source-manifest'
        ? evidence.sourceImageDigest === evidence.imageIdDigest
        : false;
if (!imageIdIsBoundToImageSource) {
  throw new Error('Docker image ID does not match its recorded registry manifest or local image identity.');
}

sbom.metadata.properties ??= [];
if (!Array.isArray(sbom.metadata.properties)) {
  throw new Error('CycloneDX metadata.properties is not an array.');
}

const bindings = new Map([
  ['fraud-detection-platform:image-ref', evidence.imageRef],
  ['fraud-detection-platform:image-id', evidence.imageIdDigest],
  ['fraud-detection-platform:scanned-digest', evidence.scannedImageDigest],
  ['fraud-detection-platform:image-platform', evidence.imagePlatform],
  ['fraud-detection-platform:tested-git-sha', evidence.testedGitSha],
  ['fraud-detection-platform:source-manifest-media-type', evidence.sourceManifestMediaType],
]);
if (evidence.platformManifestDigest) {
  bindings.set('fraud-detection-platform:platform-manifest-digest', evidence.platformManifestDigest);
}

for (const [name, value] of bindings) {
  const existing = sbom.metadata.properties.filter((property) => property?.name === name);
  if (existing.length > 1) {
    throw new Error(`Image SBOM already contains duplicate identity property ${name}.`);
  }
  if (existing.length === 1) existing[0].value = value;
  else sbom.metadata.properties.push({ name, value });
}

writeFileSync(sbomPath, `${JSON.stringify(sbom, null, 2)}\n`);
