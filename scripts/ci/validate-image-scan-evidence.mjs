#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const [inventoryPathArgument, artifactsPathArgument] = process.argv.slice(2);
if (!inventoryPathArgument || !artifactsPathArgument || process.argv.length !== 4) {
  throw new Error('Usage: node validate-image-scan-evidence.mjs <expected-inventory.json> <downloaded-artifacts-directory>');
}

const inventoryPath = resolve(inventoryPathArgument);
const artifactsPath = resolve(artifactsPathArgument);
const failures = [];
const shaPattern = /^[a-f0-9]{40}$/;
const digestPattern = /^sha256:[a-f0-9]{64}$/;
const allowedSources = new Set(['buildx-loaded-image', 'registry-pinned']);
const manifestMediaTypes = new Set([
  'application/vnd.oci.image.manifest.v1+json',
  'application/vnd.docker.distribution.manifest.v2+json',
]);
const indexMediaTypes = new Set([
  'application/vnd.oci.image.index.v1+json',
  'application/vnd.docker.distribution.manifest.list.v2+json',
]);

function readJson(file, description) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    failures.push(`${description} is missing or invalid JSON: ${error.message}`);
    return null;
  }
}

function hashFile(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex');
}

function failUnless(condition, message) {
  if (!condition) failures.push(message);
}

function isDigest(value) {
  return typeof value === 'string' && digestPattern.test(value);
}

function decodeManifest(encoded, description, expectedDigest) {
  if (typeof encoded !== 'string' || encoded.length === 0) {
    failures.push(`${description} raw manifest bytes are missing.`);
    return null;
  }
  let bytes;
  try {
    bytes = Buffer.from(encoded, 'base64');
  } catch {
    failures.push(`${description} raw manifest bytes are not valid base64.`);
    return null;
  }
  if (bytes.toString('base64') !== encoded) {
    failures.push(`${description} raw manifest bytes are not canonical base64.`);
    return null;
  }
  const actualDigest = `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
  failUnless(actualDigest === expectedDigest, `${description} raw manifest hash does not match its recorded digest.`);
  try {
    return JSON.parse(bytes.toString('utf8'));
  } catch (error) {
    failures.push(`${description} raw manifest is invalid JSON: ${error.message}`);
    return null;
  }
}

function verifyCycloneDx(sbom, description) {
  failUnless(sbom.bomFormat === 'CycloneDX'
    && typeof sbom.specVersion === 'string'
    && /^1\.[0-9]+$/.test(sbom.specVersion)
    && Number.isInteger(sbom.version)
    && sbom.version > 0
    && Array.isArray(sbom.components)
    && sbom.components.length > 0
    && sbom.metadata && typeof sbom.metadata === 'object',
  `${description}: image SBOM is not a populated CycloneDX JSON document.`);
  for (const component of Array.isArray(sbom.components) ? sbom.components : []) {
    failUnless(component && typeof component.name === 'string' && component.name.length > 0
      && typeof component.type === 'string' && component.type.length > 0,
    `${description}: image SBOM has a component without a name or CycloneDX type.`);
  }
  const bomRefs = new Set();
  for (const component of Array.isArray(sbom.components) ? sbom.components : []) {
    if (typeof component?.['bom-ref'] !== 'string') continue;
    if (bomRefs.has(component['bom-ref'])) failures.push(`${description}: image SBOM contains a duplicate bom-ref.`);
    bomRefs.add(component['bom-ref']);
  }
}

const inventory = readJson(inventoryPath, 'Expected image inventory');
if (!inventory) process.exit(1);
const expectedSha = inventory.testedGitSha;
failUnless(inventory.schemaVersion === 1, 'Expected image inventory has an unsupported schemaVersion.');
failUnless(shaPattern.test(expectedSha ?? ''), 'Expected image inventory does not contain a 40-character tested Git SHA.');
failUnless(process.env.TESTED_GIT_SHA === expectedSha,
  'Expected image inventory SHA does not match the revision selected by the workflow.');
failUnless(Array.isArray(inventory.expectedArtifacts) && inventory.expectedArtifacts.length > 0,
  'Expected image inventory must contain at least one required artifact.');

const jobResultsRaw = process.env.IMAGE_SCAN_JOB_RESULTS ?? '';
let jobResults;
try {
  jobResults = JSON.parse(jobResultsRaw);
} catch (error) {
  failures.push(`IMAGE_SCAN_JOB_RESULTS is missing or invalid JSON: ${error.message}`);
  jobResults = null;
}
if (!jobResults || typeof jobResults !== 'object' || Array.isArray(jobResults) || Object.keys(jobResults).length === 0) {
  failures.push('IMAGE_SCAN_JOB_RESULTS must contain the required scan job outcomes.');
} else {
  const requiredJobs = Array.isArray(inventory.requiredScanJobs) ? inventory.requiredScanJobs : [];
  if (requiredJobs.length === 0) failures.push('Expected image inventory must name every required scan job.');
  if (new Set(requiredJobs).size !== requiredJobs.length) failures.push('Expected image inventory contains duplicate required scan job names.');
  for (const job of requiredJobs) {
    if (!Object.hasOwn(jobResults, job)) failures.push(`Required image scan job result is missing: ${job}`);
  }
  for (const job of Object.keys(jobResults)) {
    if (!requiredJobs.includes(job)) failures.push(`Unexpected image scan job result was provided: ${job}`);
  }
  for (const [job, outcome] of Object.entries(jobResults)) {
    if (outcome !== 'success') failures.push(`Required image scan job ${job} completed with status ${String(outcome)}.`);
  }
}

if (!Array.isArray(inventory.expectedArtifacts)) process.exit(1);
const expectedNames = new Set();
for (const expected of inventory.expectedArtifacts) {
  if (!expected || typeof expected !== 'object') {
    failures.push('Expected image inventory contains a non-object entry.');
    continue;
  }
  if (typeof expected.artifactName !== 'string' || !expected.artifactName) {
    failures.push('Expected image inventory contains an artifact without a name.');
    continue;
  }
  if (expectedNames.has(expected.artifactName)) failures.push(`Expected image artifact is listed more than once: ${expected.artifactName}`);
  expectedNames.add(expected.artifactName);
  if (!allowedSources.has(expected.source)) failures.push(`${expected.artifactName}: unsupported image identity source ${String(expected.source)}.`);
  if (typeof expected.imageRef !== 'string' || !expected.imageRef || expected.imageRef.includes('$')) {
    failures.push(`${expected.artifactName}: expected image reference is missing or unresolved.`);
  }
  if (expected.expectedPlatform !== 'linux/amd64') {
    failures.push(`${expected.artifactName}: unsupported expected platform ${String(expected.expectedPlatform)}.`);
  }
}

let actualDirectories = [];
try {
  const entries = readdirSync(artifactsPath, { withFileTypes: true });
  actualDirectories = entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort();
  for (const entry of entries) {
    if (!entry.isDirectory()) failures.push(`Unexpected non-directory entry in downloaded image artifacts: ${entry.name}`);
  }
} catch (error) {
  failures.push(`Downloaded image evidence directory is missing or unreadable: ${error.message}`);
}
for (const name of [...expectedNames].sort()) {
  if (!actualDirectories.includes(name)) failures.push(`Required image evidence artifact is missing: ${name}`);
}
for (const name of actualDirectories) {
  if (!expectedNames.has(name)) failures.push(`Unexpected or duplicate image evidence artifact was downloaded: ${name}`);
}

const summaries = [];
for (const expected of inventory.expectedArtifacts) {
  if (!expected || typeof expected.artifactName !== 'string') continue;
  const artifactDirectory = join(artifactsPath, expected.artifactName);
  if (!actualDirectories.includes(expected.artifactName) || !statSync(artifactDirectory).isDirectory()) continue;
  const evidencePath = join(artifactDirectory, 'image-security-evidence.json');
  const resultPath = join(artifactDirectory, 'image-scan-result.json');
  const trivyPath = join(artifactDirectory, 'trivy-image.json');
  const rawSbomPath = join(artifactDirectory, 'image.cdx.raw.json');
  const boundSbomPath = join(artifactDirectory, 'image.cdx.json');
  const evidence = readJson(evidencePath, `${expected.artifactName}/image-security-evidence.json`);
  const result = readJson(resultPath, `${expected.artifactName}/image-scan-result.json`);
  const trivy = readJson(trivyPath, `${expected.artifactName}/trivy-image.json`);
  const rawSbom = readJson(rawSbomPath, `${expected.artifactName}/image.cdx.raw.json`);
  const boundSbom = readJson(boundSbomPath, `${expected.artifactName}/image.cdx.json`);
  const buildxMetadata = expected.source === 'buildx-loaded-image'
    ? readJson(join(artifactDirectory, 'buildx-metadata.json'), `${expected.artifactName}/buildx-metadata.json`)
    : null;
  const registryIdentity = expected.source === 'registry-pinned'
    ? readJson(join(artifactDirectory, 'registry-image-identity.json'), `${expected.artifactName}/registry-image-identity.json`)
    : null;
  if (!evidence || !result || !trivy || !rawSbom || !boundSbom
    || (expected.source === 'buildx-loaded-image' && !buildxMetadata)
    || (expected.source === 'registry-pinned' && !registryIdentity)) continue;

  const expectedRef = expected.imageRef.replaceAll('${{ github.sha }}', expectedSha);
  const imageId = evidence.imageIdDigest;
  failUnless(evidence.schemaVersion === 2, `${expected.artifactName}: unsupported image evidence schema.`);
  failUnless(evidence.testedGitSha === expectedSha, `${expected.artifactName}: scanned evidence belongs to a different Git SHA.`);
  failUnless(evidence.imageRef === expectedRef, `${expected.artifactName}: scanned image reference does not match the derived inventory.`);
  failUnless(evidence.imagePlatform === expected.expectedPlatform, `${expected.artifactName}: scanned platform does not match the required platform.`);
  failUnless(isDigest(imageId), `${expected.artifactName}: Docker image ID/config digest is invalid.`);
  failUnless(evidence.scannedImageDigest === imageId && evidence.scannedImageIdentitySource === 'docker-image-id',
    `${expected.artifactName}: scanners are not bound to the immutable Docker image ID.`);
  failUnless(Array.isArray(evidence.repoDigests), `${expected.artifactName}: repository digest evidence is missing.`);

  if (expected.source === 'buildx-loaded-image') {
    const descriptor = buildxMetadata['containerimage.descriptor'];
    const buildxManifestDigest = buildxMetadata['containerimage.digest'];
    const buildxConfigDigest = buildxMetadata['containerimage.config.digest'];
    const descriptorMediaType = descriptor?.mediaType;
    const descriptorConfig = descriptor?.annotations?.['config.digest'];
    failUnless(isDigest(buildxManifestDigest) && isDigest(buildxConfigDigest),
      `${expected.artifactName}: Buildx output metadata is missing a valid manifest or config digest.`);
    failUnless(descriptor && descriptor.digest === buildxManifestDigest
      && descriptorConfig === buildxConfigDigest && manifestMediaTypes.has(descriptorMediaType),
    `${expected.artifactName}: Buildx descriptor does not bind its output manifest and config.`);
    failUnless(evidence.buildxActionDigest === buildxManifestDigest
      && evidence.buildxDigest === buildxManifestDigest
      && evidence.buildxConfigDigest === buildxConfigDigest,
    `${expected.artifactName}: Buildx action output differs from the preserved exporter metadata.`);
    failUnless(buildxConfigDigest === imageId && evidence.platformConfigDigest === imageId,
      `${expected.artifactName}: loaded Docker image ID does not match Buildx's config digest.`);
    failUnless(evidence.dockerImageIdKind === 'docker-image-id'
      && evidence.sourceImageDigest === buildxManifestDigest
      && evidence.sourceManifestMediaType === descriptorMediaType
      && evidence.sourceManifestContentDigest === buildxManifestDigest
      && evidence.platformManifestDigest === buildxManifestDigest
      && evidence.platformManifestMediaType === descriptorMediaType
      && evidence.platformManifestContentDigest === buildxManifestDigest,
    `${expected.artifactName}: Buildx manifest/config identity evidence is inconsistent.`);
  } else if (expected.source === 'registry-pinned') {
    const pinnedDigest = expectedRef.match(/@(?<digest>sha256:[a-f0-9]{64})$/)?.groups?.digest;
    const registryRepository = expectedRef.slice(0, expectedRef.lastIndexOf('@'));
    failUnless(Boolean(pinnedDigest), `${expected.artifactName}: registry image reference is not pinned by digest.`);
    failUnless(registryIdentity.repository === registryRepository
      && registryIdentity.expectedPlatform === expected.expectedPlatform,
    `${expected.artifactName}: registry identity was inspected for another repository or platform.`);
    const sourceManifest = decodeManifest(registryIdentity.sourceManifestRawBase64,
      `${expected.artifactName}/source`, pinnedDigest);
    const platformManifest = decodeManifest(registryIdentity.platformManifestRawBase64,
      `${expected.artifactName}/platform`, registryIdentity.platformManifestDigest);
    const sourceIsIndex = indexMediaTypes.has(registryIdentity.sourceManifestMediaType);
    const sourceIsManifest = manifestMediaTypes.has(registryIdentity.sourceManifestMediaType);
    if (sourceIsIndex && sourceManifest) {
      const [expectedOs, expectedArchitecture] = expected.expectedPlatform.split('/');
      const matching = Array.isArray(sourceManifest.manifests) ? sourceManifest.manifests.filter((entry) =>
        entry?.platform?.os === expectedOs && entry?.platform?.architecture === expectedArchitecture) : [];
      failUnless(matching.length === 1 && matching[0].digest === registryIdentity.platformManifestDigest,
        `${expected.artifactName}: registry index does not identify exactly one expected platform manifest.`);
    } else if (sourceIsManifest) {
      failUnless(registryIdentity.platformManifestDigest === pinnedDigest
        && registryIdentity.platformManifestRawBase64 === registryIdentity.sourceManifestRawBase64,
      `${expected.artifactName}: direct platform manifest identity differs from the pinned registry manifest.`);
    } else {
      failures.push(`${expected.artifactName}: registry source uses an unsupported OCI manifest media type.`);
    }
    failUnless(platformManifest && manifestMediaTypes.has(platformManifest.mediaType)
      && platformManifest.config?.digest === imageId
      && registryIdentity.platformConfigDigest === imageId,
    `${expected.artifactName}: loaded Docker image ID does not match the independently hashed platform config.`);
    failUnless(registryIdentity.sourceImageDigest === pinnedDigest
      && registryIdentity.sourceManifestContentDigest === pinnedDigest
      && registryIdentity.platformManifestContentDigest === registryIdentity.platformManifestDigest
      && registryIdentity.platformManifestMediaType === platformManifest?.mediaType
      && registryIdentity.dockerImageIdKind === 'platform-config',
    `${expected.artifactName}: registry manifest provenance is inconsistent with its raw content.`);
    failUnless(evidence.buildxDigest === null && evidence.buildxConfigDigest === null
      && evidence.dockerImageIdKind === 'platform-config'
      && evidence.sourceImageDigest === pinnedDigest
      && evidence.sourceManifestMediaType === registryIdentity.sourceManifestMediaType
      && evidence.sourceManifestContentDigest === pinnedDigest
      && evidence.platformManifestDigest === registryIdentity.platformManifestDigest
      && evidence.platformManifestMediaType === registryIdentity.platformManifestMediaType
      && evidence.platformManifestContentDigest === registryIdentity.platformManifestContentDigest
      && evidence.platformConfigDigest === imageId,
    `${expected.artifactName}: evidence does not preserve the pinned registry/index/platform/config relationship.`);
    failUnless(evidence.repoDigests.some((repoDigest) => typeof repoDigest === 'string'
      && (repoDigest.endsWith(`@${pinnedDigest}`)
        || repoDigest.endsWith(`@${registryIdentity.platformManifestDigest}`))),
    `${expected.artifactName}: Docker RepoDigests do not reference the pinned source or selected platform manifest.`);
  }

  const identityFields = [
    'testedGitSha', 'imageRef', 'imagePlatform', 'imageIdDigest', 'dockerImageIdKind',
    'scannedImageDigest', 'scannedImageIdentitySource', 'repoDigests', 'sourceImageDigest',
    'sourceManifestMediaType', 'sourceManifestContentDigest', 'platformManifestDigest',
    'platformManifestMediaType', 'platformManifestContentDigest', 'platformConfigDigest',
    'buildxDigest', 'buildxActionDigest', 'buildxConfigDigest',
  ];
  for (const field of identityFields) {
    failUnless(JSON.stringify(result[field]) === JSON.stringify(evidence[field]),
      `${expected.artifactName}: scan result identity field ${field} differs from captured image evidence.`);
  }
  failUnless(result.scannerInputImageId === imageId,
    `${expected.artifactName}: recorded scanner input is not the immutable Docker image ID.`);

  const expectedHashes = {
    evidence: hashFile(evidencePath),
    trivy: hashFile(trivyPath),
    sbomRaw: hashFile(rawSbomPath),
    sbomBound: hashFile(boundSbomPath),
    buildxMetadata: expected.source === 'buildx-loaded-image' ? hashFile(join(artifactDirectory, 'buildx-metadata.json')) : null,
    registryIdentity: expected.source === 'registry-pinned' ? hashFile(join(artifactDirectory, 'registry-image-identity.json')) : null,
  };
  for (const [name, digest] of Object.entries(expectedHashes)) {
    failUnless(result.artifactSha256?.[name] === digest,
      `${expected.artifactName}: SHA-256 evidence for ${name} is missing or incorrect.`);
  }

  failUnless(result.testedGitSha === expectedSha && result.imageRef === expectedRef,
    `${expected.artifactName}: scan result does not match the expected revision and image reference.`);
  failUnless(result.imagePlatform === expected.expectedPlatform,
    `${expected.artifactName}: scan result platform does not match the expected platform.`);
  failUnless(result.scanner?.name === 'trivy'
    && result.scanner.version === 'v0.76.2'
    && result.scanner.outcome === 'success'
    && result.scanner.policy === 'HIGH,CRITICAL',
  `${expected.artifactName}: Trivy did not complete with the required version and HIGH/CRITICAL policy.`);
  failUnless(result.sbom?.format === 'CycloneDX JSON'
    && result.sbom.generator === 'syft'
    && result.sbom.version === 'v1.57.2'
    && result.sbom.outcome === 'success'
    && result.sbom.bindingOutcome === 'success',
  `${expected.artifactName}: CycloneDX generation or image identity binding did not complete successfully.`);

  failUnless(trivy.SchemaVersion === 2 && typeof trivy.ArtifactName === 'string'
    && Array.isArray(trivy.Results),
  `${expected.artifactName}: Trivy JSON does not have the expected report structure.`);
  failUnless(trivy.ArtifactName === imageId,
    `${expected.artifactName}: Trivy report does not identify the immutable Docker image ID it scanned.`);
  failUnless(trivy.Metadata && typeof trivy.Metadata === 'object' && trivy.Metadata.ImageID === imageId,
    `${expected.artifactName}: Trivy report does not identify the captured Docker image config digest.`);
  for (const scanResult of Array.isArray(trivy.Results) ? trivy.Results : []) {
    if (!scanResult || typeof scanResult !== 'object'
      || (scanResult.Vulnerabilities !== undefined && !Array.isArray(scanResult.Vulnerabilities))) {
      failures.push(`${expected.artifactName}: Trivy JSON contains an invalid scan result entry.`);
      continue;
    }
    for (const vulnerability of scanResult.Vulnerabilities ?? []) {
      if (['HIGH', 'CRITICAL'].includes(vulnerability?.Severity)) {
        failures.push(`${expected.artifactName}: Trivy reports ${vulnerability.Severity} ${vulnerability.VulnerabilityID ?? 'vulnerability'} in ${vulnerability.PkgName ?? 'an image package'}.`);
      }
    }
  }

  verifyCycloneDx(rawSbom, `${expected.artifactName} raw`);
  verifyCycloneDx(boundSbom, `${expected.artifactName} bound`);
  const requiredProperties = new Map([
    ['fraud-detection-platform:image-ref', expectedRef],
    ['fraud-detection-platform:image-id', imageId],
    ['fraud-detection-platform:scanned-digest', imageId],
    ['fraud-detection-platform:scanner-input-image-id', imageId],
    ['fraud-detection-platform:image-platform', expected.expectedPlatform],
    ['fraud-detection-platform:tested-git-sha', expectedSha],
    ['fraud-detection-platform:source-image-digest', evidence.sourceImageDigest],
    ['fraud-detection-platform:source-manifest-media-type', evidence.sourceManifestMediaType],
    ['fraud-detection-platform:source-manifest-content-digest', evidence.sourceManifestContentDigest],
    ['fraud-detection-platform:platform-manifest-digest', evidence.platformManifestDigest],
    ['fraud-detection-platform:platform-manifest-content-digest', evidence.platformManifestContentDigest],
    ['fraud-detection-platform:platform-config-digest', evidence.platformConfigDigest],
  ]);
  const rawProperties = Array.isArray(rawSbom.metadata?.properties) ? rawSbom.metadata.properties : [];
  const boundProperties = Array.isArray(boundSbom.metadata?.properties) ? boundSbom.metadata.properties : [];
  const derivedPropertyNames = new Set(requiredProperties.keys());
  failUnless(!rawProperties.some((property) => typeof property?.name === 'string'
    && property.name.startsWith('fraud-detection-platform:')),
  `${expected.artifactName}: raw SBOM already contains derived identity properties.`);
  for (const [propertyName, propertyValue] of requiredProperties) {
    const matches = boundProperties.filter((property) => property?.name === propertyName);
    failUnless(matches.length === 1 && matches[0].value === propertyValue,
      `${expected.artifactName}: SBOM identity binding ${propertyName} is missing, duplicated, or mismatched.`);
  }
  const unboundRaw = structuredClone(rawSbom);
  const unboundBound = structuredClone(boundSbom);
  unboundRaw.metadata.properties ??= [];
  unboundBound.metadata.properties = boundProperties.filter((property) => !derivedPropertyNames.has(property?.name));
  failUnless(JSON.stringify(unboundRaw) === JSON.stringify(unboundBound),
    `${expected.artifactName}: identity binding changed raw Syft SBOM evidence outside the derived identity properties.`);

  summaries.push({
    artifactName: expected.artifactName,
    imageRef: expectedRef,
    scannedImageDigest: evidence.scannedImageDigest,
    imageIdDigest: evidence.imageIdDigest,
    imagePlatform: evidence.imagePlatform,
    testedGitSha: evidence.testedGitSha,
    trivyOutcome: result.scanner?.outcome ?? null,
    sbomOutcome: result.sbom?.outcome ?? null,
    sbomComponentCount: Array.isArray(boundSbom.components) ? boundSbom.components.length : 0,
  });
}

failUnless(summaries.length === inventory.expectedArtifacts.length,
  'Not every expected image artifact produced a complete evidence summary.');

const summaryPath = join(dirname(inventoryPath), 'validated-image-scan-summary.json');
mkdirSync(dirname(inventoryPath), { recursive: true });
writeFileSync(summaryPath, `${JSON.stringify({
  schemaVersion: 2,
  outcome: failures.length === 0 ? 'success' : 'failure',
  testedGitSha: expectedSha,
  requiredScanJobs: jobResults,
  expectedArtifactCount: inventory.expectedArtifacts.length,
  artifactCount: summaries.length,
  artifacts: summaries,
  failures,
}, null, 2)}\n`);

if (failures.length > 0) {
  console.error('Image scan evidence validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Validated ${summaries.length} image scan and CycloneDX evidence artifacts for ${expectedSha}.`);
