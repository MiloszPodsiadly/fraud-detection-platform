#!/usr/bin/env node
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { decodeHashedManifest, sha256File, verifyCycloneDxDocument } from './image-evidence-integrity.mjs';
import { imageSbomIdentityBindings, isImageSbomIdentityProperty } from './image-sbom-identity.mjs';
import { parseDockerImageInspect, verifyImageEngineBinding } from './image-identity.mjs';
import { expectedScanCategories } from './image-scan-contract.mjs';

const [inventoryPathArgument, artifactsPathArgument] = process.argv.slice(2);
if (!inventoryPathArgument || !artifactsPathArgument || process.argv.length !== 4) {
  throw new Error('Usage: node validate-image-scan-evidence.mjs <expected-inventory.json> <downloaded-artifacts-directory>');
}

const inventoryPath = resolve(inventoryPathArgument);
const artifactsPath = resolve(artifactsPathArgument);
const evidenceFiles = SECURITY_POLICY.artifacts.imageEvidenceFiles;
const failures = [];
const shaPattern = /^[a-f0-9]{40}$/;
const digestPattern = /^sha256:[a-f0-9]{64}$/;
const allowedSources = new Set(SECURITY_POLICY.imageIdentity.allowedSources);
const manifestMediaTypes = new Set(SECURITY_POLICY.imageIdentity.manifestMediaTypes);
const indexMediaTypes = new Set(SECURITY_POLICY.imageIdentity.indexMediaTypes);

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

function isDigest(value) {
  return typeof value === 'string' && digestPattern.test(value);
}

function writeDiagnosticSummary({ testedGitSha, requiredScanJobs, expectedArtifactCount, summaries }) {
  const summaryPath = join(dirname(inventoryPath), 'validated-image-scan-summary.json');
  mkdirSync(dirname(inventoryPath), { recursive: true });
  const artifactDiagnostics = (Array.isArray(inventory?.expectedArtifacts) ? inventory.expectedArtifacts : [])
    .filter((expected) => typeof expected?.artifactName === 'string')
    .map((expected) => {
      const artifactFailures = failures.filter((failure) => failure.includes(expected.artifactName));
      const validated = summaries.some((summary) => summary.artifactName === expected.artifactName);
      return {
        artifactName: expected.artifactName,
        status: validated && artifactFailures.length === 0 ? 'success' : 'failure',
        missingEvidence: artifactFailures.filter((failure) => /missing|unavailable|invalid JSON/i.test(failure)),
        failures: artifactFailures,
      };
    });
  const missingEvidence = failures.filter((failure) => /missing|unavailable|invalid JSON/i.test(failure));
  writeFileSync(summaryPath, `${JSON.stringify({
    schemaVersion: 2,
    outcome: failures.length === 0 ? 'success' : 'failure',
    scanStatus: failures.length === 0 ? 'success' : 'failure',
    errorCategory: failures.length === 0 ? null
      : missingEvidence.length > 0 ? 'missing-or-invalid-evidence' : 'evidence-validation-failure',
    testedGitSha,
    requiredScanJobs,
    expectedArtifactCount,
    artifactCount: summaries.length,
    artifacts: summaries,
    artifactDiagnostics,
    missingEvidence,
    failures,
  }, null, 2)}\n`);
}

const inventory = readJson(inventoryPath, 'Expected image inventory');
if (!inventory || typeof inventory !== 'object' || Array.isArray(inventory)) {
  if (failures.length === 0) failures.push('Expected image inventory must be a JSON object.');
  writeDiagnosticSummary({ testedGitSha: null, requiredScanJobs: null, expectedArtifactCount: null, summaries: [] });
  process.exit(1);
}
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

if (!Array.isArray(inventory.expectedArtifacts)) {
  writeDiagnosticSummary({
    testedGitSha: expectedSha,
    requiredScanJobs: jobResults ?? null,
    expectedArtifactCount: null,
    summaries: [],
  });
  process.exit(1);
}
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
  if (expected.expectedPlatform !== SECURITY_POLICY.platforms.image) {
    failures.push(`${expected.artifactName}: unsupported expected platform ${String(expected.expectedPlatform)}.`);
  }
  const requiredCategories = expectedScanCategories(expected);
  if (!Array.isArray(expected.expectedScanCategories)
    || JSON.stringify(expected.expectedScanCategories) !== JSON.stringify(requiredCategories)) {
    failures.push(`${expected.artifactName}: expected scan categories do not match its image type.`);
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
  try {
  const artifactDirectory = join(artifactsPath, expected.artifactName);
  if (!actualDirectories.includes(expected.artifactName) || !statSync(artifactDirectory).isDirectory()) continue;
  const evidencePath = join(artifactDirectory, evidenceFiles.evidence);
  const dockerInspectPath = join(artifactDirectory, evidenceFiles.dockerInspect);
  const dockerInspectAfterPath = join(artifactDirectory, evidenceFiles.dockerInspectAfterScan);
  const resultPath = join(artifactDirectory, evidenceFiles.scanResult);
  const trivyPath = join(artifactDirectory, evidenceFiles.trivy);
  const rawSbomPath = join(artifactDirectory, evidenceFiles.rawSbom);
  const boundSbomPath = join(artifactDirectory, evidenceFiles.boundSbom);
  const evidence = readJson(evidencePath, `${expected.artifactName}/${evidenceFiles.evidence}`);
  const dockerInspect = readJson(dockerInspectPath, `${expected.artifactName}/${evidenceFiles.dockerInspect}`);
  const dockerInspectAfter = readJson(dockerInspectAfterPath, `${expected.artifactName}/${evidenceFiles.dockerInspectAfterScan}`);
  const result = readJson(resultPath, `${expected.artifactName}/${evidenceFiles.scanResult}`);
  const trivy = readJson(trivyPath, `${expected.artifactName}/${evidenceFiles.trivy}`);
  const rawSbom = readJson(rawSbomPath, `${expected.artifactName}/${evidenceFiles.rawSbom}`);
  const boundSbom = readJson(boundSbomPath, `${expected.artifactName}/${evidenceFiles.boundSbom}`);
  const buildxMetadata = expected.source === 'buildx-loaded-image'
    ? readJson(join(artifactDirectory, evidenceFiles.buildxMetadata), `${expected.artifactName}/${evidenceFiles.buildxMetadata}`)
    : null;
  const registryIdentity = expected.source === 'registry-pinned'
    ? readJson(join(artifactDirectory, evidenceFiles.registryIdentity), `${expected.artifactName}/${evidenceFiles.registryIdentity}`)
    : null;
  if (!evidence || !dockerInspect || !dockerInspectAfter || !result || !trivy || !rawSbom || !boundSbom
    || (expected.source === 'buildx-loaded-image' && !buildxMetadata)
    || (expected.source === 'registry-pinned' && !registryIdentity)) continue;

  const expectedRef = expected.imageRef.replaceAll('${{ github.sha }}', expectedSha);
  const imageId = evidence.imageIdDigest;
  const dockerInspectRecord = Array.isArray(dockerInspect) ? dockerInspect[0] : dockerInspect;
  const dockerInspectAfterRecord = Array.isArray(dockerInspectAfter) ? dockerInspectAfter[0] : dockerInspectAfter;
  failUnless(dockerInspectRecord?.Id === imageId && dockerInspectAfterRecord?.Id === imageId
    && dockerInspectRecord?.Os === expected.expectedPlatform.split('/')[0]
    && dockerInspectRecord?.Architecture === expected.expectedPlatform.split('/')[1]
    && dockerInspectAfterRecord?.Os === dockerInspectRecord?.Os
    && dockerInspectAfterRecord?.Architecture === dockerInspectRecord?.Architecture
    && JSON.stringify(dockerInspectAfterRecord?.Descriptor ?? null) === JSON.stringify(dockerInspectRecord?.Descriptor ?? null)
    && JSON.stringify(dockerInspectAfterRecord?.RepoTags ?? []) === JSON.stringify(dockerInspectRecord?.RepoTags ?? [])
    && JSON.stringify(dockerInspectAfterRecord?.RepoDigests ?? []) === JSON.stringify(dockerInspectRecord?.RepoDigests ?? []),
  `${expected.artifactName}: Docker Engine image reference changed after scan execution.`);
  failUnless(evidence.schemaVersion === 3, `${expected.artifactName}: unsupported image evidence schema.`);
  failUnless(evidence.testedGitSha === expectedSha, `${expected.artifactName}: scanned evidence belongs to a different Git SHA.`);
  failUnless(evidence.imageRef === expectedRef, `${expected.artifactName}: scanned image reference does not match the derived inventory.`);
  failUnless(evidence.imagePlatform === expected.expectedPlatform, `${expected.artifactName}: scanned platform does not match the required platform.`);
  failUnless(isDigest(imageId), `${expected.artifactName}: Docker Engine image ID is invalid.`);
  failUnless(evidence.scannedImageDigest === imageId && evidence.scannedImageIdentitySource === 'docker-engine-image-id'
    && evidence.dockerEngineId === imageId,
  `${expected.artifactName}: scanner input differs from the captured Docker Engine image ID.`);
  failUnless(isDigest(evidence.dockerEngineConfigDigest)
    && evidence.dockerEngineConfigDigest === evidence.platformConfigDigest,
  `${expected.artifactName}: Docker Engine config digest is missing or differs from the independently selected platform config.`);
  failUnless(Array.isArray(evidence.repoDigests), `${expected.artifactName}: repository digest evidence is missing.`);

  if (expected.source === 'buildx-loaded-image') {
    const descriptor = buildxMetadata['containerimage.descriptor'];
    const buildxManifestDigest = buildxMetadata['containerimage.digest'];
    const buildxConfigDigest = buildxMetadata['containerimage.config.digest'];
    const descriptorMediaType = descriptor?.mediaType;
    const descriptorConfig = descriptor?.annotations?.['config.digest'];
    failUnless(isDigest(buildxManifestDigest)
      && (buildxConfigDigest === undefined || isDigest(buildxConfigDigest)),
    `${expected.artifactName}: Buildx output metadata has a malformed manifest or reported config digest.`);
    failUnless(descriptor && descriptor.digest === buildxManifestDigest
      && manifestMediaTypes.has(descriptorMediaType)
      && (descriptorConfig === undefined || buildxConfigDigest === undefined || descriptorConfig === buildxConfigDigest),
    `${expected.artifactName}: Buildx descriptor does not bind its output manifest.`);
    failUnless(evidence.buildxActionDigest === buildxManifestDigest
      && evidence.buildxDigest === buildxManifestDigest
      && isDigest(evidence.buildxConfigDigest)
      && (buildxConfigDigest === undefined || evidence.buildxConfigDigest === buildxConfigDigest)
      && (descriptorConfig === undefined || evidence.buildxConfigDigest === descriptorConfig),
    `${expected.artifactName}: Buildx action output differs from the preserved exporter metadata or hashed OCI config.`);
    failUnless(evidence.buildxConfigDigest === evidence.dockerEngineConfigDigest
      && /^[a-f0-9]{64}$/.test(evidence.buildxOciArchiveSha256 ?? ''),
    `${expected.artifactName}: hashed OCI export does not bind the Docker Engine config identity.`);
    failUnless(['config-digest', 'manifest-descriptor'].includes(evidence.dockerImageIdKind)
      && evidence.sourceImageDigest === buildxManifestDigest
      && evidence.sourceManifestMediaType === descriptorMediaType
      && evidence.sourceManifestContentDigest === buildxManifestDigest
      && evidence.platformManifestDigest === buildxManifestDigest
      && evidence.platformManifestMediaType === descriptorMediaType
      && evidence.platformManifestContentDigest === buildxManifestDigest,
    `${expected.artifactName}: Buildx manifest/config identity evidence is inconsistent.`);
    try {
      const inspected = parseDockerImageInspect(
        JSON.stringify(Array.isArray(dockerInspect) ? dockerInspect : [dockerInspect]), expected.expectedPlatform,
      );
      const binding = verifyImageEngineBinding({
        image: inspected,
        expectedPlatform: expected.expectedPlatform,
        expectedRef,
        manifestDigest: buildxManifestDigest,
        manifestMediaType: descriptorMediaType,
        configDigest: evidence.buildxConfigDigest,
        acceptedRepoDigests: [buildxManifestDigest],
      });
      failUnless(binding.dockerEngineId === imageId
        && binding.dockerEngineIdKind === evidence.dockerImageIdKind
        && binding.dockerEngineConfigDigest === evidence.dockerEngineConfigDigest
        && binding.dockerEngineDescriptorDigest === evidence.dockerEngineDescriptorDigest
        && binding.dockerEngineDescriptorMediaType === evidence.dockerEngineDescriptorMediaType
        && binding.dockerEngineDescriptorConfigDigest === evidence.dockerEngineDescriptorConfigDigest,
      `${expected.artifactName}: preserved Docker inspection does not prove the recorded Buildx identity.`);
      failUnless(JSON.stringify(dockerInspectRecord?.RepoDigests ?? []) === JSON.stringify(evidence.repoDigests),
        `${expected.artifactName}: recorded RepoDigests differ from Docker Engine inspection.`);
    } catch (error) {
      failures.push(`${expected.artifactName}: Buildx Docker Engine identity verification failed: ${error.message}`);
    }
  } else if (expected.source === 'registry-pinned') {
    const pinnedDigest = expectedRef.match(/@(?<digest>sha256:[a-f0-9]{64})$/)?.groups?.digest;
    const registryRepository = expectedRef.slice(0, expectedRef.lastIndexOf('@'));
    failUnless(Boolean(pinnedDigest), `${expected.artifactName}: registry image reference is not pinned by digest.`);
    failUnless(registryIdentity.repository === registryRepository
      && registryIdentity.expectedPlatform === expected.expectedPlatform,
    `${expected.artifactName}: registry identity was inspected for another repository or platform.`);
    const sourceManifest = decodeHashedManifest(registryIdentity.sourceManifestRawBase64,
      `${expected.artifactName}/source`, pinnedDigest, failures);
    const platformManifest = decodeHashedManifest(registryIdentity.platformManifestRawBase64,
      `${expected.artifactName}/platform`, registryIdentity.platformManifestDigest, failures);
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
      && platformManifest.config?.digest === evidence.dockerEngineConfigDigest
      && registryIdentity.platformConfigDigest === evidence.dockerEngineConfigDigest,
    `${expected.artifactName}: Docker Engine config does not match the independently hashed platform config.`);
    failUnless(registryIdentity.sourceImageDigest === pinnedDigest
      && registryIdentity.sourceManifestContentDigest === pinnedDigest
      && registryIdentity.platformManifestContentDigest === registryIdentity.platformManifestDigest
      && registryIdentity.platformManifestMediaType === platformManifest?.mediaType
      && ['config-digest', 'manifest-descriptor', 'source-index-descriptor'].includes(registryIdentity.dockerEngineIdKind)
      && registryIdentity.dockerEngineId === imageId
      && registryIdentity.dockerEngineConfigDigest === evidence.dockerEngineConfigDigest
      && registryIdentity.dockerEngineDescriptorDigest === evidence.dockerEngineDescriptorDigest
      && registryIdentity.dockerEngineDescriptorMediaType === evidence.dockerEngineDescriptorMediaType
      && registryIdentity.dockerEngineDescriptorConfigDigest === evidence.dockerEngineDescriptorConfigDigest,
    `${expected.artifactName}: registry manifest provenance is inconsistent with its raw content.`);
    failUnless(evidence.buildxDigest === null && evidence.buildxConfigDigest === null
      && evidence.dockerImageIdKind === registryIdentity.dockerEngineIdKind
      && evidence.sourceImageDigest === pinnedDigest
      && evidence.sourceManifestMediaType === registryIdentity.sourceManifestMediaType
      && evidence.sourceManifestContentDigest === pinnedDigest
      && evidence.platformManifestDigest === registryIdentity.platformManifestDigest
      && evidence.platformManifestMediaType === registryIdentity.platformManifestMediaType
      && evidence.platformManifestContentDigest === registryIdentity.platformManifestContentDigest
      && evidence.platformConfigDigest === evidence.dockerEngineConfigDigest,
    `${expected.artifactName}: evidence does not preserve the pinned registry/index/platform/config relationship.`);
    failUnless(evidence.repoDigests.some((repoDigest) => typeof repoDigest === 'string'
      && (repoDigest.endsWith(`@${pinnedDigest}`)
        || repoDigest.endsWith(`@${registryIdentity.platformManifestDigest}`))),
    `${expected.artifactName}: Docker RepoDigests do not reference the pinned source or selected platform manifest.`);
    try {
      const inspected = parseDockerImageInspect(
        JSON.stringify(Array.isArray(dockerInspect) ? dockerInspect : [dockerInspect]), expected.expectedPlatform,
      );
      const binding = verifyImageEngineBinding({
        image: inspected,
        expectedPlatform: expected.expectedPlatform,
        expectedRef,
        manifestDigest: registryIdentity.platformManifestDigest,
        manifestMediaType: registryIdentity.platformManifestMediaType,
        configDigest: registryIdentity.platformConfigDigest,
        sourceDigest: registryIdentity.sourceImageDigest,
        sourceMediaType: registryIdentity.sourceManifestMediaType,
        acceptedRepoDigests: [pinnedDigest, registryIdentity.platformManifestDigest],
        requireRepoDigest: true,
      });
      failUnless(binding.dockerEngineId === imageId
        && binding.dockerEngineIdKind === evidence.dockerImageIdKind
        && binding.dockerEngineConfigDigest === evidence.dockerEngineConfigDigest
        && binding.dockerEngineDescriptorDigest === evidence.dockerEngineDescriptorDigest
        && binding.dockerEngineDescriptorMediaType === evidence.dockerEngineDescriptorMediaType
        && binding.dockerEngineDescriptorConfigDigest === evidence.dockerEngineDescriptorConfigDigest,
      `${expected.artifactName}: preserved Docker inspection does not prove the recorded registry identity.`);
      failUnless(JSON.stringify(dockerInspectRecord?.RepoDigests ?? []) === JSON.stringify(evidence.repoDigests),
        `${expected.artifactName}: recorded RepoDigests differ from Docker Engine inspection.`);
    } catch (error) {
      failures.push(`${expected.artifactName}: registry Docker Engine identity verification failed: ${error.message}`);
    }
  }

  const identityFields = [
    'testedGitSha', 'imageRef', 'imagePlatform', 'imageIdDigest', 'dockerImageIdKind',
    'dockerEngineId', 'dockerEngineConfigDigest', 'dockerEngineDescriptorDigest',
    'dockerEngineDescriptorMediaType', 'dockerEngineDescriptorConfigDigest',
    'scannedImageDigest', 'scannedImageIdentitySource', 'repoDigests', 'sourceImageDigest',
    'sourceManifestMediaType', 'sourceManifestContentDigest', 'platformManifestDigest',
    'platformManifestMediaType', 'platformManifestContentDigest', 'platformConfigDigest',
    'buildxDigest', 'buildxActionDigest', 'buildxConfigDigest', 'buildxOciArchiveSha256',
  ];
  for (const field of identityFields) {
    failUnless(JSON.stringify(result[field]) === JSON.stringify(evidence[field]),
      `${expected.artifactName}: scan result identity field ${field} differs from captured image evidence.`);
  }
  failUnless(result.scannerInputImageId === imageId,
    `${expected.artifactName}: recorded scanner input is not the immutable Docker image ID.`);

  const expectedHashes = {
    evidence: sha256File(evidencePath),
    dockerImageInspect: sha256File(dockerInspectPath),
    dockerImageInspectAfterScan: sha256File(dockerInspectAfterPath),
    trivy: sha256File(trivyPath),
    sbomRaw: sha256File(rawSbomPath),
    sbomBound: sha256File(boundSbomPath),
    buildxMetadata: expected.source === 'buildx-loaded-image' ? sha256File(join(artifactDirectory, evidenceFiles.buildxMetadata)) : null,
    registryIdentity: expected.source === 'registry-pinned' ? sha256File(join(artifactDirectory, evidenceFiles.registryIdentity)) : null,
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
    && result.scanner.version === `v${SECURITY_POLICY.tools.trivy}`
    && result.scanner.outcome === 'success'
    && result.scanner.policy === SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities.join(','),
  `${expected.artifactName}: Trivy did not complete with the required version and ${SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities.join('/')} policy.`);
  failUnless(result.sbom?.format === SECURITY_POLICY.formats.imageSbom
    && result.sbom.generator === 'syft'
    && result.sbom.version === `v${SECURITY_POLICY.tools.syft}`
    && result.sbom.outcome === 'success'
    && result.sbom.bindingOutcome === 'success',
  `${expected.artifactName}: CycloneDX generation or image identity binding did not complete successfully.`);

  failUnless(trivy.SchemaVersion === SECURITY_POLICY.formats.trivyImageSchemaVersion && typeof trivy.ArtifactName === 'string'
    && Array.isArray(trivy.Results),
  `${expected.artifactName}: Trivy JSON does not have the expected report structure.`);
  const trivyResults = Array.isArray(trivy.Results) ? trivy.Results : null;
  failUnless(trivy.ArtifactName === imageId,
    `${expected.artifactName}: Trivy report target does not match its immutable scanner input.`);
  failUnless(trivy.Metadata && typeof trivy.Metadata === 'object'
    && trivy.Metadata.ImageID === evidence.dockerEngineConfigDigest,
  `${expected.artifactName}: Trivy report does not identify the Docker Engine image config digest.`);
  const expectedCategories = Array.isArray(expected.expectedScanCategories) ? expected.expectedScanCategories : [];
  const observedCategories = new Set((trivyResults ?? [])
    .filter((entry) => entry && typeof entry === 'object' && typeof entry.Class === 'string')
    .map((entry) => entry.Class));
  for (const category of expectedCategories) {
    failUnless(observedCategories.has(category),
      `${expected.artifactName}: Trivy report is missing expected ${category} package scan results.`);
    failUnless((trivyResults ?? []).some((entry) => entry?.Class === category
      && typeof entry.Target === 'string' && entry.Target.length > 0),
    `${expected.artifactName}: expected ${category} scan result has no target.`);
  }
  if (expectedCategories.length > 0) {
    failUnless(trivyResults !== null && trivyResults.length > 0,
      `${expected.artifactName}: Trivy Results is missing, invalid, or empty for an image with expected packages.`);
  }
  if (expectedCategories.includes('os-pkgs')) {
    failUnless(typeof trivy.Metadata.OS?.Family === 'string' && trivy.Metadata.OS.Family.length > 0,
      `${expected.artifactName}: Trivy OS package scan has no detected OS identity.`);
  }
  for (const scanResult of trivyResults ?? []) {
    if (!scanResult || typeof scanResult !== 'object'
      || (scanResult.Vulnerabilities !== undefined && !Array.isArray(scanResult.Vulnerabilities))) {
      failures.push(`${expected.artifactName}: Trivy JSON contains an invalid scan result entry.`);
      continue;
    }
    for (const vulnerability of scanResult.Vulnerabilities ?? []) {
      if (SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities.includes(vulnerability?.Severity)) {
        failures.push(`${expected.artifactName}: Trivy reports ${vulnerability.Severity} ${vulnerability.VulnerabilityID ?? 'vulnerability'} in ${vulnerability.PkgName ?? 'an image package'}.`);
      }
    }
  }

  verifyCycloneDxDocument(rawSbom, `${expected.artifactName} raw`, failures);
  verifyCycloneDxDocument(boundSbom, `${expected.artifactName} bound`, failures);
  const requiredProperties = imageSbomIdentityBindings({
    ...evidence,
    imageRef: expectedRef,
    imageIdDigest: imageId,
    scannedImageDigest: imageId,
    imagePlatform: expected.expectedPlatform,
    testedGitSha: expectedSha,
  });
  const rawProperties = Array.isArray(rawSbom.metadata?.properties) ? rawSbom.metadata.properties : [];
  const boundProperties = Array.isArray(boundSbom.metadata?.properties) ? boundSbom.metadata.properties : [];
  const derivedPropertyNames = new Set(requiredProperties.keys());
  failUnless(!rawProperties.some((property) => isImageSbomIdentityProperty(property?.name)),
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
  } catch (error) {
    const errorCategory = error instanceof TypeError ? 'malformed artifact structure' : 'artifact validation exception';
    failures.push(`${expected.artifactName}: validation stopped safely after ${errorCategory}.`);
  }
}

failUnless(summaries.length === inventory.expectedArtifacts.length,
  'Not every expected image artifact produced a complete evidence summary.');

writeDiagnosticSummary({
  testedGitSha: expectedSha,
  requiredScanJobs: jobResults,
  expectedArtifactCount: inventory.expectedArtifacts.length,
  summaries,
});

if (failures.length > 0) {
  console.error('Image scan evidence validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Validated ${summaries.length} image scan and CycloneDX evidence artifacts for ${expectedSha}.`);
