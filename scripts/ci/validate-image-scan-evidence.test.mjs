#!/usr/bin/env node
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const validator = resolve(scriptDirectory, 'validate-image-scan-evidence.mjs');
const testedGitSha = 'a'.repeat(40);
const imageIdDigest = `sha256:${'1'.repeat(64)}`;
const manifestDigest = `sha256:${'2'.repeat(64)}`;
const platformManifestDigest = `sha256:${'3'.repeat(64)}`;
const artifactName = `backend-image-alert-service-security-${testedGitSha}`;

function baselineFixture() {
  return {
    inventory: {
      schemaVersion: 1,
      testedGitSha,
      requiredScanJobs: ['backend'],
      expectedArtifacts: [{
        artifactName,
        logicalName: 'alert-service',
        imageRef: `fdp-local/alert-service:${testedGitSha}`,
        expectedPlatform: 'linux/amd64',
        source: 'buildx-loaded-image',
      }],
    },
    evidence: {
      testedGitSha,
      imageRef: `fdp-local/alert-service:${testedGitSha}`,
      imagePlatform: 'linux/amd64',
      scannedImageDigest: manifestDigest,
      scannedImageIdentitySource: 'buildx-manifest-verified-against-image-id',
      imageIdDigest,
      repoDigests: [],
      sourceImageDigest: manifestDigest,
      sourceManifestMediaType: 'buildx-output-manifest',
      platformManifestDigest: manifestDigest,
      platformManifestMediaType: 'buildx-output-manifest',
      platformConfigDigest: imageIdDigest,
      buildxDigest: manifestDigest,
      buildxConfigDigest: imageIdDigest,
    },
    result: {
      testedGitSha,
      imageRef: `fdp-local/alert-service:${testedGitSha}`,
      imagePlatform: 'linux/amd64',
      imageIdDigest,
      scannedImageDigest: manifestDigest,
      scannedImageIdentitySource: 'buildx-manifest-verified-against-image-id',
      repoDigests: [],
      sourceImageDigest: manifestDigest,
      sourceManifestMediaType: 'buildx-output-manifest',
      platformManifestDigest: manifestDigest,
      platformManifestMediaType: 'buildx-output-manifest',
      platformConfigDigest: imageIdDigest,
      buildxDigest: manifestDigest,
      buildxConfigDigest: imageIdDigest,
      scanner: { name: 'trivy', version: 'v0.76.2', outcome: 'success', policy: 'HIGH,CRITICAL' },
      sbom: { format: 'CycloneDX JSON', generator: 'syft', version: 'v1.57.2', outcome: 'success', bindingOutcome: 'success' },
    },
    trivy: {
      SchemaVersion: 2,
      ArtifactName: `fdp-local/alert-service:${testedGitSha}`,
      Metadata: { ImageID: imageIdDigest },
      Results: [],
    },
    sbom: {
      bomFormat: 'CycloneDX',
      specVersion: '1.6',
      version: 1,
      metadata: {
        properties: [
          { name: 'fraud-detection-platform:image-ref', value: `fdp-local/alert-service:${testedGitSha}` },
          { name: 'fraud-detection-platform:image-id', value: imageIdDigest },
          { name: 'fraud-detection-platform:scanned-digest', value: manifestDigest },
          { name: 'fraud-detection-platform:image-platform', value: 'linux/amd64' },
          { name: 'fraud-detection-platform:tested-git-sha', value: testedGitSha },
          { name: 'fraud-detection-platform:source-manifest-media-type', value: 'buildx-output-manifest' },
          { name: 'fraud-detection-platform:platform-manifest-digest', value: manifestDigest },
        ],
      },
      components: [{ name: 'alpine-baselayout', type: 'library', version: '3.6.5', 'bom-ref': 'pkg:apk/alpine-baselayout@3.6.5' }],
    },
  };
}

function runValidator(mutate = () => {}) {
  const root = mkdtempSync(join(tmpdir(), 'fdp157-image-evidence-'));
  try {
    const inventoryPath = join(root, 'expected-inventory.json');
    const artifactDirectory = join(root, 'downloads', artifactName);
    mkdirSync(artifactDirectory, { recursive: true });
    const fixture = baselineFixture();
    mutate(fixture, artifactDirectory);
    writeFileSync(inventoryPath, `${JSON.stringify(fixture.inventory)}\n`);
    writeFileSync(join(artifactDirectory, 'image-security-evidence.json'), `${JSON.stringify(fixture.evidence)}\n`);
    writeFileSync(join(artifactDirectory, 'image-scan-result.json'), `${JSON.stringify(fixture.result)}\n`);
    if (fixture.trivy !== null) writeFileSync(join(artifactDirectory, 'trivy-image.json'), `${JSON.stringify(fixture.trivy)}\n`);
    if (fixture.sbom !== null) writeFileSync(join(artifactDirectory, 'image.cdx.json'), `${JSON.stringify(fixture.sbom)}\n`);
    if (fixture.trivy === 'invalid-json') writeFileSync(join(artifactDirectory, 'trivy-image.json'), '{');
    if (fixture.omitArtifact) rmSync(artifactDirectory, { recursive: true, force: true });

    return spawnSync(process.execPath, [validator, inventoryPath, join(root, 'downloads')], {
      encoding: 'utf8',
      env: { ...process.env, TESTED_GIT_SHA: testedGitSha, IMAGE_SCAN_JOB_RESULTS: '{"backend":"success"}' },
    });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function useRegistryImage(fixture, sourceIsIndex = true) {
  const imageRef = `registry.example/security/image:1@${manifestDigest}`;
  fixture.inventory.expectedArtifacts[0] = {
    ...fixture.inventory.expectedArtifacts[0],
    imageRef,
    source: 'registry-pinned',
  };
  const sourceMediaType = sourceIsIndex
    ? 'application/vnd.oci.image.index.v1+json'
    : 'application/vnd.oci.image.manifest.v1+json';
  const selectedPlatformDigest = sourceIsIndex ? platformManifestDigest : manifestDigest;
  const selectedPlatformMediaType = 'application/vnd.oci.image.manifest.v1+json';
  const repoDigest = sourceIsIndex ? `registry.example/security/image@${manifestDigest}` : `registry.example/security/image@${manifestDigest}`;
  Object.assign(fixture.evidence, {
    imageRef,
    imageIdDigest,
    scannedImageDigest: manifestDigest,
    scannedImageIdentitySource: 'immutable-image-reference',
    repoDigests: [repoDigest],
    sourceImageDigest: manifestDigest,
    sourceManifestMediaType: sourceMediaType,
    platformManifestDigest: selectedPlatformDigest,
    platformManifestMediaType: selectedPlatformMediaType,
    platformConfigDigest: imageIdDigest,
    buildxDigest: null,
    buildxConfigDigest: null,
  });
  Object.assign(fixture.result, {
    imageRef,
    imageIdDigest,
    scannedImageDigest: manifestDigest,
    scannedImageIdentitySource: 'immutable-image-reference',
    repoDigests: [repoDigest],
    sourceImageDigest: manifestDigest,
    sourceManifestMediaType: sourceMediaType,
    platformManifestDigest: selectedPlatformDigest,
    platformManifestMediaType: selectedPlatformMediaType,
    platformConfigDigest: imageIdDigest,
    buildxDigest: null,
    buildxConfigDigest: null,
  });
  fixture.trivy.ArtifactName = imageRef;
  const values = new Map([
    ['fraud-detection-platform:image-ref', imageRef],
    ['fraud-detection-platform:scanned-digest', manifestDigest],
    ['fraud-detection-platform:source-manifest-media-type', sourceMediaType],
    ['fraud-detection-platform:platform-manifest-digest', selectedPlatformDigest],
  ]);
  for (const [name, value] of values) {
    fixture.sbom.metadata.properties.find((property) => property.name === name).value = value;
  }
}

test('accepts complete successful image scan and identity-bound SBOM evidence', () => {
  const result = runValidator();
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('accepts a pinned OCI index when its selected platform manifest matches the image config', () => {
  const result = runValidator((fixture) => useRegistryImage(fixture, true));
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('accepts a digest that directly identifies one platform manifest', () => {
  const result = runValidator((fixture) => useRegistryImage(fixture, false));
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('rejects a missing artifact', () => {
  const result = runValidator((fixture) => { fixture.omitArtifact = true; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /missing/i);
});

test('rejects an extra artifact directory', () => {
  const result = runValidator((_fixture, artifactDirectory) => mkdirSync(join(dirname(artifactDirectory), 'unexpected')));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unexpected/i);
});

test('rejects a duplicated inventory artifact', () => {
  const result = runValidator((fixture) => fixture.inventory.expectedArtifacts.push({ ...fixture.inventory.expectedArtifacts[0] }));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /more than once/i);
});

test('rejects invalid Trivy JSON', () => {
  const result = runValidator((fixture) => { fixture.trivy = 'invalid-json'; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /invalid JSON/i);
});

test('rejects a Trivy report for a different image config digest', () => {
  const result = runValidator((fixture) => { fixture.trivy.Metadata.ImageID = manifestDigest; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Trivy report does not identify the image config digest/i);
});

test('rejects a Trivy report for a different image reference', () => {
  const result = runValidator((fixture) => { fixture.trivy.ArtifactName = 'registry.example/unexpected:latest'; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Trivy report scanned a different image reference/i);
});

test('rejects a failed scanner outcome', () => {
  const result = runValidator((fixture) => { fixture.result.scanner.outcome = 'failure'; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Trivy did not complete/i);
});

test('rejects a HIGH vulnerability finding', () => {
  const result = runValidator((fixture) => {
    fixture.trivy.Results = [{ Vulnerabilities: [{ Severity: 'HIGH', VulnerabilityID: 'TEST-HIGH', PkgName: 'fixture' }] }];
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /reports HIGH/i);
});

test('rejects stale revision evidence', () => {
  const result = runValidator((fixture) => { fixture.evidence.testedGitSha = 'b'.repeat(40); });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /different Git SHA/i);
});

test('rejects a mismatched Buildx manifest digest', () => {
  const result = runValidator((fixture) => { fixture.evidence.buildxDigest = `sha256:${'3'.repeat(64)}`; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /manifest digest does not match/i);
});

test('rejects an unexpected platform', () => {
  const result = runValidator((fixture) => { fixture.inventory.expectedArtifacts[0].expectedPlatform = 'linux/arm64'; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unsupported expected platform/i);
});

test('rejects missing image SBOM evidence', () => {
  const result = runValidator((fixture) => { fixture.sbom = null; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /image\.cdx\.json is missing/i);
});

test('rejects an SBOM bound to another image digest', () => {
  const result = runValidator((fixture) => {
    fixture.sbom.metadata.properties.find((property) => property.name === 'fraud-detection-platform:scanned-digest').value = imageIdDigest;
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /SBOM identity binding/i);
});

test('rejects a failed or cancelled source job even when artifacts exist', () => {
  const root = mkdtempSync(join(tmpdir(), 'fdp157-image-evidence-status-'));
  try {
    const inventoryPath = join(root, 'expected-inventory.json');
    const fixture = baselineFixture();
    fixture.inventory.expectedArtifacts = [];
    writeFileSync(inventoryPath, JSON.stringify(fixture.inventory));
    const result = spawnSync(process.execPath, [validator, inventoryPath, join(root, 'downloads')], {
      encoding: 'utf8',
      env: { ...process.env, TESTED_GIT_SHA: testedGitSha, IMAGE_SCAN_JOB_RESULTS: '{"backend":"cancelled"}' },
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /status cancelled/i);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
