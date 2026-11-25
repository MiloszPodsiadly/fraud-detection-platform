#!/usr/bin/env node
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
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
const allowedSources = new Set(['buildx-loaded-image', 'locally-built', 'registry-pinned']);

function readJson(file, description) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    failures.push(`${description} is missing or invalid JSON: ${error.message}`);
    return null;
  }
}

function failUnless(condition, message) {
  if (!condition) failures.push(message);
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
const expectedSorted = [...expectedNames].sort();
for (const name of expectedSorted) {
  if (!actualDirectories.includes(name)) failures.push(`Required image evidence artifact is missing: ${name}`);
}
for (const name of actualDirectories) {
  if (!expectedNames.has(name)) failures.push(`Unexpected or duplicate image evidence artifact was downloaded: ${name}`);
}

const summaries = [];
for (const expected of inventory.expectedArtifacts) {
  if (!expected || typeof expected.artifactName !== 'string') continue;
  const artifactDirectory = join(artifactsPath, expected.artifactName);
  if (!actualDirectories.includes(expected.artifactName)) continue;
  if (!statSync(artifactDirectory).isDirectory()) continue;
  if (typeof expected.imageRef !== 'string' || !expected.imageRef) continue;

  const evidence = readJson(join(artifactDirectory, 'image-security-evidence.json'), `${expected.artifactName}/image-security-evidence.json`);
  const result = readJson(join(artifactDirectory, 'image-scan-result.json'), `${expected.artifactName}/image-scan-result.json`);
  const trivy = readJson(join(artifactDirectory, 'trivy-image.json'), `${expected.artifactName}/trivy-image.json`);
  const sbom = readJson(join(artifactDirectory, 'image.cdx.json'), `${expected.artifactName}/image.cdx.json`);
  if (!evidence || !result || !trivy || !sbom) continue;

  const expectedRef = expected.imageRef.replaceAll('${{ github.sha }}', expectedSha);
  failUnless(evidence.testedGitSha === expectedSha, `${expected.artifactName}: scanned evidence belongs to a different Git SHA.`);
  failUnless(evidence.imageRef === expectedRef, `${expected.artifactName}: scanned image reference does not match the derived inventory.`);
  failUnless(evidence.imagePlatform === expected.expectedPlatform, `${expected.artifactName}: scanned platform does not match the required platform.`);
  failUnless(digestPattern.test(evidence.imageIdDigest ?? ''), `${expected.artifactName}: image config/image ID digest is invalid.`);
  failUnless(digestPattern.test(evidence.scannedImageDigest ?? ''), `${expected.artifactName}: immutable scanned image digest is invalid.`);
  failUnless(evidence.platformConfigDigest === null || digestPattern.test(evidence.platformConfigDigest ?? ''),
    `${expected.artifactName}: selected platform config digest is invalid.`);
  failUnless(['platform-config', 'platform-manifest', 'source-manifest', 'docker-image-id'].includes(evidence.dockerImageIdKind),
    `${expected.artifactName}: Docker image ID kind is missing or unsupported.`);
  failUnless(Array.isArray(evidence.repoDigests), `${expected.artifactName}: repository digest evidence is missing.`);
  failUnless(evidence.sourceImageDigest === evidence.scannedImageDigest,
    `${expected.artifactName}: scanned digest does not identify the selected source image.`);

  if (expected.source === 'buildx-loaded-image') {
    failUnless(evidence.scannedImageIdentitySource === 'buildx-loaded-image-id'
      && evidence.dockerImageIdKind === 'docker-image-id'
      && evidence.scannedImageDigest === evidence.imageIdDigest
      && evidence.sourceImageDigest === evidence.imageIdDigest,
    `${expected.artifactName}: Buildx-loaded local image ID is not the identity Trivy analyzed.`);
    failUnless(evidence.sourceManifestMediaType === 'local-image-config'
      && evidence.platformManifestMediaType === 'local-image-config'
      && evidence.platformManifestDigest === null
      && evidence.platformConfigDigest === evidence.buildxConfigDigest,
    `${expected.artifactName}: local Buildx metadata is not separated from the loaded Docker image ID.`);
    failUnless(evidence.buildxDigest === null || digestPattern.test(evidence.buildxDigest),
      `${expected.artifactName}: Buildx output manifest digest is malformed.`);
    failUnless(evidence.buildxConfigDigest === null || digestPattern.test(evidence.buildxConfigDigest),
      `${expected.artifactName}: Buildx output config digest is malformed.`);
  } else if (expected.source === 'locally-built') {
    failUnless(evidence.scannedImageIdentitySource === 'docker-image-id',
      `${expected.artifactName}: locally built image identity does not come from the Docker image ID.`);
    failUnless(evidence.dockerImageIdKind === 'docker-image-id'
      && evidence.scannedImageDigest === evidence.imageIdDigest,
    `${expected.artifactName}: locally built image digest does not match its Docker image ID.`);
    failUnless(evidence.sourceImageDigest === evidence.imageIdDigest
      && evidence.sourceManifestMediaType === 'local-image-config'
      && evidence.platformManifestDigest === null
      && evidence.platformManifestMediaType === 'local-image-config'
      && evidence.platformConfigDigest === null,
    `${expected.artifactName}: local image config identity evidence is incomplete.`);
  } else if (expected.source === 'registry-pinned') {
    const pinnedDigest = expectedRef.match(/@(?<digest>sha256:[a-f0-9]{64})$/)?.groups?.digest;
    const indexMediaTypes = new Set([
      'application/vnd.oci.image.index.v1+json',
      'application/vnd.docker.distribution.manifest.list.v2+json',
    ]);
    const manifestMediaTypes = new Set([
      'application/vnd.oci.image.manifest.v1+json',
      'application/vnd.docker.distribution.manifest.v2+json',
    ]);
    failUnless(Boolean(pinnedDigest), `${expected.artifactName}: registry image reference is not pinned by digest.`);
    failUnless(evidence.scannedImageIdentitySource === 'immutable-image-reference',
      `${expected.artifactName}: registry image identity was not recorded from its immutable reference.`);
    failUnless(evidence.scannedImageDigest === pinnedDigest,
      `${expected.artifactName}: scanned digest does not match the pinned registry reference.`);
    failUnless(Array.isArray(evidence.repoDigests)
      && evidence.repoDigests.some((repoDigest) => typeof repoDigest === 'string'
        && (repoDigest.endsWith(`@${pinnedDigest}`) || repoDigest.endsWith(`@${evidence.platformManifestDigest}`))),
    `${expected.artifactName}: Docker RepoDigests do not match the pinned index or selected platform manifest.`);
    const sourceIsIndex = indexMediaTypes.has(evidence.sourceManifestMediaType);
    const sourceIsManifest = manifestMediaTypes.has(evidence.sourceManifestMediaType);
    const platformManifestMatchesSource = sourceIsIndex
      ? evidence.platformManifestMediaType !== evidence.sourceManifestMediaType
        && evidence.platformManifestDigest !== pinnedDigest
      : evidence.platformManifestDigest === pinnedDigest
        && evidence.platformManifestMediaType === evidence.sourceManifestMediaType;
    failUnless((sourceIsIndex || sourceIsManifest)
      && manifestMediaTypes.has(evidence.platformManifestMediaType)
      && digestPattern.test(evidence.platformManifestDigest ?? '')
      && platformManifestMatchesSource,
    `${expected.artifactName}: pinned registry image does not record its OCI index/manifest and selected platform manifest consistently.`);
    const imageIdMatchesRecordedIdentity = evidence.dockerImageIdKind === 'platform-config'
      ? evidence.platformConfigDigest === evidence.imageIdDigest
      : evidence.dockerImageIdKind === 'platform-manifest'
        ? evidence.platformManifestDigest === evidence.imageIdDigest
        : evidence.dockerImageIdKind === 'source-manifest'
          ? evidence.sourceImageDigest === evidence.imageIdDigest
          : false;
    failUnless(imageIdMatchesRecordedIdentity,
      `${expected.artifactName}: Docker image ID does not match an independently inspected registry identity.`);
  }

  failUnless(result.testedGitSha === expectedSha && result.imageRef === expectedRef,
    `${expected.artifactName}: scan result does not match the expected revision and image reference.`);
  failUnless(result.imagePlatform === expected.expectedPlatform,
    `${expected.artifactName}: scan result platform does not match the expected platform.`);
  failUnless(result.imageIdDigest === evidence.imageIdDigest
    && result.dockerImageIdKind === evidence.dockerImageIdKind
    && result.scannedImageDigest === evidence.scannedImageDigest
    && result.scannedImageIdentitySource === evidence.scannedImageIdentitySource,
  `${expected.artifactName}: scan result identity differs from image evidence.`);
  failUnless(result.buildxDigest === evidence.buildxDigest
    && result.buildxConfigDigest === evidence.buildxConfigDigest,
  `${expected.artifactName}: scan result omits or changes Buildx digest evidence.`);
  failUnless(result.sourceImageDigest === evidence.sourceImageDigest
    && result.sourceManifestMediaType === evidence.sourceManifestMediaType
    && result.platformManifestDigest === evidence.platformManifestDigest
    && result.platformManifestMediaType === evidence.platformManifestMediaType
    && result.platformConfigDigest === evidence.platformConfigDigest,
  `${expected.artifactName}: scan result platform-manifest identity differs from image inspection evidence.`);
  failUnless(JSON.stringify(result.repoDigests) === JSON.stringify(evidence.repoDigests),
    `${expected.artifactName}: scan result repository digest evidence differs from the image inspection evidence.`);

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
    && trivy.ArtifactName.length > 0 && Array.isArray(trivy.Results),
  `${expected.artifactName}: Trivy JSON does not have the expected report structure.`);
  failUnless(trivy.ArtifactName === expectedRef,
    `${expected.artifactName}: Trivy report scanned a different image reference.`);
  failUnless(trivy.Metadata && typeof trivy.Metadata === 'object'
    && trivy.Metadata.ImageID === evidence.imageIdDigest,
  `${expected.artifactName}: Trivy report does not identify the image config digest recorded by Docker inspection.`);
  for (const scanResult of (Array.isArray(trivy.Results) ? trivy.Results : [])) {
    if (!scanResult || typeof scanResult !== 'object' || (scanResult.Vulnerabilities !== undefined && !Array.isArray(scanResult.Vulnerabilities))) {
      failures.push(`${expected.artifactName}: Trivy JSON contains an invalid scan result entry.`);
      continue;
    }
    for (const vulnerability of scanResult.Vulnerabilities ?? []) {
      if (['HIGH', 'CRITICAL'].includes(vulnerability?.Severity)) {
        failures.push(`${expected.artifactName}: Trivy reports ${vulnerability.Severity} ${vulnerability.VulnerabilityID ?? 'vulnerability'} in ${vulnerability.PkgName ?? 'an image package'}.`);
      }
    }
  }

  failUnless(sbom.bomFormat === 'CycloneDX'
    && typeof sbom.specVersion === 'string'
    && /^1\.[0-9]+$/.test(sbom.specVersion)
    && Number.isInteger(sbom.version)
    && sbom.version > 0
    && Array.isArray(sbom.components)
    && sbom.components.length > 0
    && sbom.metadata && typeof sbom.metadata === 'object',
  `${expected.artifactName}: image SBOM is not a populated CycloneDX JSON document.`);
  const sbomProperties = Array.isArray(sbom.metadata?.properties) ? sbom.metadata.properties : [];
  const requiredProperties = new Map([
    ['fraud-detection-platform:image-ref', expectedRef],
    ['fraud-detection-platform:image-id', evidence.imageIdDigest],
    ['fraud-detection-platform:scanned-digest', evidence.scannedImageDigest],
    ['fraud-detection-platform:image-platform', expected.expectedPlatform],
    ['fraud-detection-platform:tested-git-sha', expectedSha],
    ['fraud-detection-platform:source-manifest-media-type', evidence.sourceManifestMediaType],
  ]);
  if (evidence.platformManifestDigest) {
    requiredProperties.set('fraud-detection-platform:platform-manifest-digest', evidence.platformManifestDigest);
  }
  for (const [propertyName, propertyValue] of requiredProperties) {
    const matches = sbomProperties.filter((property) => property?.name === propertyName);
    failUnless(matches.length === 1 && matches[0].value === propertyValue,
      `${expected.artifactName}: SBOM identity binding ${propertyName} is missing, duplicated, or mismatched.`);
  }
  const bomRefs = new Set();
  for (const component of (Array.isArray(sbom.components) ? sbom.components : [])) {
    failUnless(component && typeof component.name === 'string' && component.name.length > 0
      && typeof component.type === 'string' && component.type.length > 0,
    `${expected.artifactName}: image SBOM has a component without a name or CycloneDX type.`);
    if (typeof component?.['bom-ref'] === 'string') {
      if (bomRefs.has(component['bom-ref'])) failures.push(`${expected.artifactName}: image SBOM contains a duplicate bom-ref.`);
      bomRefs.add(component['bom-ref']);
    }
  }

  summaries.push({
    artifactName: expected.artifactName,
    imageRef: expectedRef,
    scannedImageDigest: evidence.scannedImageDigest,
    imageIdDigest: evidence.imageIdDigest,
    imagePlatform: evidence.imagePlatform,
    testedGitSha: evidence.testedGitSha,
    trivyOutcome: result.scanner?.outcome ?? null,
    sbomOutcome: result.sbom?.outcome ?? null,
    sbomComponentCount: Array.isArray(sbom.components) ? sbom.components.length : 0,
  });
}

failUnless(summaries.length === inventory.expectedArtifacts.length,
  'Not every expected image artifact produced a complete evidence summary.');

const summaryPath = join(dirname(inventoryPath), 'validated-image-scan-summary.json');
mkdirSync(dirname(summaryPath), { recursive: true });
writeFileSync(summaryPath, `${JSON.stringify({
  schemaVersion: 1,
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
