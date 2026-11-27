#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { parseDockerImageInspect } from './image-identity.mjs';

const [outputDirectoryArgument] = process.argv.slice(2);
if (!outputDirectoryArgument || process.argv.length !== 3) {
  throw new Error('Usage: node write-image-security-evidence.mjs <output-directory>');
}
const outputDirectory = resolve(outputDirectoryArgument);
const evidenceFile = SECURITY_POLICY.artifacts.imageEvidenceFiles.evidence;
const requiredEnv = ['TESTED_GIT_SHA', 'IMAGE_REF', 'EXPECTED_PLATFORM', 'IMAGE_IDENTITY_JSON', 'IDENTITY_SOURCE'];
for (const name of requiredEnv) if (!process.env[name]) throw new Error(`${name} is required.`);
if (!/^[a-f0-9]{40}$/.test(process.env.TESTED_GIT_SHA)) throw new Error('TESTED_GIT_SHA must be a full Git SHA.');

let identity;
try {
  identity = JSON.parse(process.env.IMAGE_IDENTITY_JSON);
} catch {
  throw new Error('Verified image identity output is not valid JSON.');
}
const inspectJson = readFileSync(join(outputDirectory, 'docker-image-inspect.json'), 'utf8');
const image = parseDockerImageInspect(inspectJson, process.env.EXPECTED_PLATFORM);
if (identity.dockerEngineId !== image.Id || identity.imagePlatform !== `${image.Os}/${image.Architecture}`) {
  throw new Error('Verified identity output does not describe the preserved Docker Engine inspection.');
}
if (process.env.IDENTITY_SOURCE === 'buildx-loaded-image') {
  if (identity.dockerEngineConfigDigest !== identity.configDigest
    || identity.dockerEngineDescriptorDigest !== null && identity.dockerEngineDescriptorDigest !== identity.manifestDigest) {
    throw new Error('Verified Buildx identity has inconsistent manifest, config, or Engine descriptor fields.');
  }
  if (!/^[a-f0-9]{64}$/.test(identity.archiveSha256 ?? '')
    || identity.manifestContentDigest !== identity.manifestDigest
    || identity.configContentDigest !== identity.configDigest) {
    throw new Error('Verified Buildx identity is missing content-addressed OCI archive, manifest, or config evidence.');
  }
} else if (process.env.IDENTITY_SOURCE === 'registry-pinned') {
  const platformDescriptorMismatch = identity.dockerEngineIdKind === 'manifest-descriptor'
    && identity.platformManifestDigest !== identity.dockerEngineDescriptorDigest;
  const sourceIndexDescriptorMismatch = identity.dockerEngineIdKind === 'source-index-descriptor'
    && (identity.sourceImageDigest !== identity.dockerEngineId
      || identity.sourceImageDigest !== identity.dockerEngineDescriptorDigest
      || identity.sourceManifestMediaType !== identity.dockerEngineDescriptorMediaType);
  if (identity.dockerEngineConfigDigest !== identity.platformConfigDigest
    || platformDescriptorMismatch || sourceIndexDescriptorMismatch) {
    throw new Error('Verified registry identity has inconsistent platform manifest, config, or Engine descriptor fields.');
  }
} else {
  throw new Error(`Unsupported image identity source ${process.env.IDENTITY_SOURCE}.`);
}

const nullable = (value) => value ?? null;
const evidence = {
  schemaVersion: SECURITY_POLICY.imageIdentity.evidenceSchemaVersion,
  testedGitSha: process.env.TESTED_GIT_SHA,
  imageRef: process.env.IMAGE_REF,
  imagePlatform: `${image.Os}/${image.Architecture}`,
  scannedImageDigest: image.Id,
  scannedImageIdentitySource: 'docker-engine-image-id',
  imageIdDigest: image.Id,
  dockerImageIdKind: identity.dockerEngineIdKind,
  dockerEngineId: identity.dockerEngineId,
  dockerEngineConfigDigest: identity.dockerEngineConfigDigest,
  dockerEngineDescriptorDigest: nullable(identity.dockerEngineDescriptorDigest),
  dockerEngineDescriptorMediaType: nullable(identity.dockerEngineDescriptorMediaType),
  dockerEngineDescriptorConfigDigest: nullable(identity.dockerEngineDescriptorConfigDigest),
  repoDigests: Array.isArray(image.RepoDigests) ? image.RepoDigests : [],
  sourceImageDigest: nullable(identity.sourceImageDigest ?? identity.manifestDigest),
  sourceManifestMediaType: nullable(identity.sourceManifestMediaType ?? identity.mediaType),
  sourceManifestContentDigest: nullable(identity.sourceManifestContentDigest ?? identity.manifestDigest),
  platformManifestDigest: nullable(identity.platformManifestDigest ?? identity.manifestDigest),
  platformManifestMediaType: nullable(identity.platformManifestMediaType ?? identity.mediaType),
  platformManifestContentDigest: nullable(identity.platformManifestContentDigest ?? identity.manifestDigest),
  platformConfigDigest: nullable(identity.platformConfigDigest ?? identity.configDigest),
  buildxActionDigest: process.env.IDENTITY_SOURCE === 'buildx-loaded-image' ? process.env.BUILD_DIGEST : null,
  buildxDigest: process.env.IDENTITY_SOURCE === 'buildx-loaded-image' ? identity.manifestDigest : null,
  buildxConfigDigest: process.env.IDENTITY_SOURCE === 'buildx-loaded-image' ? identity.configDigest : null,
  buildxOciArchiveSha256: process.env.IDENTITY_SOURCE === 'buildx-loaded-image' ? identity.archiveSha256 : null,
};
if (evidence.dockerEngineConfigDigest !== evidence.platformConfigDigest
  || evidence.scannedImageDigest !== evidence.dockerEngineId) {
  throw new Error('Captured Docker Engine ID is not independently bound to its platform config digest.');
}
writeFileSync(join(outputDirectory, evidenceFile), `${JSON.stringify(evidence, null, 2)}\n`);
process.stdout.write(`${evidence.imageIdDigest}\n`);
