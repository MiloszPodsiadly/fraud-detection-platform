#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { verifyOciImageArchive } from './oci-image-archive.mjs';

const [producerDirectoryArgument, consumersDirectoryArgument, outputPathArgument] = process.argv.slice(2);
if (!producerDirectoryArgument || !consumersDirectoryArgument || !outputPathArgument || process.argv.length !== 5) {
  throw new Error('Usage: node validate-kafka-image-consumers.mjs <producer-artifact-dir> <consumer-artifacts-dir> <summary.json>');
}

const producerDirectory = resolve(producerDirectoryArgument);
const consumersDirectory = resolve(consumersDirectoryArgument);
const outputPath = resolve(outputPathArgument);
const testedGitSha = process.env.TESTED_GIT_SHA ?? '';
const runId = String(process.env.GITHUB_RUN_ID ?? '');
const runAttempt = String(process.env.GITHUB_RUN_ATTEMPT ?? '');
const failures = [];
const producerEvidencePath = join(producerDirectory, 'producer-evidence.json');
const archivePath = join(producerDirectory, 'image.oci.tar');
const metadataPath = join(producerDirectory, 'buildx-metadata.json');
const digestPath = join(producerDirectory, 'buildx-digest.txt');

function readJson(filePath, description) {
  try {
    return JSON.parse(readFileSync(filePath, 'utf8'));
  } catch (error) {
    failures.push(`${description} is missing or malformed: ${error.message}`);
    return null;
  }
}

function sha256File(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

const producerEvidence = readJson(producerEvidencePath, 'Kafka image producer evidence');
const metadata = readJson(metadataPath, 'Kafka Buildx metadata');
let archiveIdentity = null;
if (producerEvidence && metadata) {
  try {
    const actionDigest = readFileSync(digestPath, 'utf8').trim();
    archiveIdentity = verifyOciImageArchive({
      archivePath,
      expectedRef: producerEvidence.imageRef,
      expectedPlatform: SECURITY_POLICY.platforms.image,
      metadata,
      actionDigest,
    });
    if (archiveIdentity.manifestDigest !== producerEvidence.buildxManifestDigest
      || archiveIdentity.configDigest !== producerEvidence.buildxConfigDigest
      || archiveIdentity.archiveSha256 !== producerEvidence.buildxOciArchiveSha256
      || actionDigest !== producerEvidence.buildxActionDigest) {
      failures.push('Kafka producer evidence does not match the downloaded OCI artifact bytes.');
    }
  } catch (error) {
    failures.push(`Kafka OCI artifact verification failed: ${error.message}`);
  }
}

if (!/^[a-f0-9]{40}$/.test(testedGitSha)) failures.push('Tested Git SHA is missing or malformed.');
if (!/^\d+$/.test(runId) || !/^\d+$/.test(runAttempt)) failures.push('Workflow run ID or attempt is missing or malformed.');
if (producerEvidence && (producerEvidence.testedGitSha !== testedGitSha
  || String(producerEvidence.runId) !== runId
  || String(producerEvidence.runAttempt) !== runAttempt
  || producerEvidence.imagePlatform !== SECURITY_POLICY.platforms.image)) {
  failures.push('Kafka producer evidence does not belong to the selected revision, run, attempt, and platform.');
}

const expectedConsumers = SECURITY_POLICY.platformGate.kafkaImageConsumers;
let actualArtifactDirectories = [];
try {
  actualArtifactDirectories = readdirSync(consumersDirectory, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
} catch (error) {
  failures.push(`Kafka consumer evidence directory is unavailable: ${error.message}`);
}
const consumerPrefix = SECURITY_POLICY.platformGate.kafkaImageConsumerArtifactPrefix;
const expectedArtifactDirectories = expectedConsumers
  .map((job) => `${consumerPrefix}-${job}-${runId}-${runAttempt}-${testedGitSha}`)
  .sort();
for (const artifactName of expectedArtifactDirectories) {
  if (!actualArtifactDirectories.includes(artifactName)) failures.push(`Required Kafka consumer artifact is missing: ${artifactName}.`);
}
for (const artifactName of actualArtifactDirectories) {
  if (!expectedArtifactDirectories.includes(artifactName)) failures.push(`Unexpected Kafka consumer artifact was downloaded: ${artifactName}.`);
}

const fieldsToMatch = [
  'testedGitSha', 'runId', 'runAttempt', 'imageRef', 'imagePlatform', 'imageIdDigest',
  'dockerImageIdKind', 'buildxActionDigest', 'buildxManifestDigest', 'buildxConfigDigest',
  'buildxOciArchiveSha256', 'dockerfileSha256', 'kafkaVersion', 'javaVersion',
  'kafkaArchiveSha512', 'compressionJars', 'dockerUser',
];
const consumers = [];
for (const job of expectedConsumers) {
  const artifactName = `${consumerPrefix}-${job}-${runId}-${runAttempt}-${testedGitSha}`;
  if (!actualArtifactDirectories.includes(artifactName)) continue;
  const evidencePath = join(consumersDirectory, artifactName, 'kafka-test-image-evidence.json');
  const evidence = readJson(evidencePath, `${job} Kafka consumer evidence`);
  if (!evidence) continue;
  if (evidence.job !== job) failures.push(`${job} Kafka evidence records another consumer job.`);
  for (const field of fieldsToMatch) {
    if (JSON.stringify(evidence[field]) !== JSON.stringify(producerEvidence?.[field])) {
      failures.push(`${job} Kafka consumer identity differs from producer field ${field}.`);
    }
  }
  consumers.push({
    job,
    evidenceSha256: sha256File(evidencePath),
    imageIdDigest: evidence.imageIdDigest,
    manifestDigest: evidence.buildxManifestDigest,
    configDigest: evidence.buildxConfigDigest,
    ociArchiveSha256: evidence.buildxOciArchiveSha256,
  });
}

let jobResults = {};
try {
  jobResults = JSON.parse(process.env.KAFKA_CONSUMER_JOB_RESULTS ?? '');
} catch (error) {
  failures.push(`Kafka consumer job results are missing or malformed: ${error.message}`);
}
const requiredStatusJobs = SECURITY_POLICY.platformGate.kafkaImageRequiredStatusJobs;
for (const job of requiredStatusJobs) {
  if (jobResults[job] !== 'success') failures.push(`Kafka consumer prerequisite ${job} completed with ${String(jobResults[job] ?? 'missing')}.`);
}

const summary = {
  schemaVersion: 1,
  outcome: failures.length === 0 ? 'success' : 'failure',
  testedGitSha,
  runId,
  runAttempt,
  imageRef: producerEvidence?.imageRef ?? null,
  imagePlatform: producerEvidence?.imagePlatform ?? null,
  imageIdDigest: producerEvidence?.imageIdDigest ?? null,
  buildxManifestDigest: archiveIdentity?.manifestDigest ?? producerEvidence?.buildxManifestDigest ?? null,
  buildxConfigDigest: archiveIdentity?.configDigest ?? producerEvidence?.buildxConfigDigest ?? null,
  ociArchiveSha256: archiveIdentity?.archiveSha256 ?? producerEvidence?.buildxOciArchiveSha256 ?? null,
  producerEvidenceSha256: producerEvidence ? sha256File(producerEvidencePath) : null,
  producerArtifactBytes: archiveIdentity ? statSync(archivePath).size : null,
  requiredConsumers: expectedConsumers,
  consumerCount: consumers.length,
  consumers,
  failures,
};
mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(summary, null, 2)}\n`);
if (failures.length > 0) {
  console.error('Kafka image consumer validation failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log(`Validated the shared Kafka OCI artifact across ${consumers.length} same-run consumers.`);
}
