#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const validator = resolve(scriptDirectory, 'validate-image-scan-evidence.mjs');
const testedGitSha = 'a'.repeat(40);
const imageIdDigest = `sha256:${'1'.repeat(64)}`;
const buildxManifestDigest = `sha256:${'2'.repeat(64)}`;
const platformManifestDigest = `sha256:${'3'.repeat(64)}`;
const artifactName = `backend-image-alert-service-security-${testedGitSha}`;
const imageRef = `fdp-local/alert-service:${testedGitSha}`;
const buildxMediaType = 'application/vnd.oci.image.manifest.v1+json';
const registryManifestMediaType = 'application/vnd.oci.image.manifest.v1+json';
const registryIndexMediaType = 'application/vnd.oci.image.index.v1+json';

function digest(bytes) {
  return `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
}

function jsonBytes(value) {
  return Buffer.from(JSON.stringify(value));
}

function rawSbom() {
  return {
    bomFormat: 'CycloneDX',
    specVersion: '1.6',
    version: 1,
    metadata: { component: { name: 'image', type: 'container' }, properties: [] },
    components: [{ name: 'alpine-baselayout', type: 'library', version: '3.6.5', 'bom-ref': 'pkg:apk/alpine-baselayout@3.6.5' }],
  };
}

function derivedIdentityProperties(evidence) {
  return [
    ['fraud-detection-platform:image-ref', evidence.imageRef],
    ['fraud-detection-platform:image-id', evidence.imageIdDigest],
    ['fraud-detection-platform:scanned-digest', evidence.scannedImageDigest],
    ['fraud-detection-platform:scanner-input-image-id', evidence.imageIdDigest],
    ['fraud-detection-platform:image-platform', evidence.imagePlatform],
    ['fraud-detection-platform:tested-git-sha', evidence.testedGitSha],
    ['fraud-detection-platform:source-image-digest', evidence.sourceImageDigest],
    ['fraud-detection-platform:source-manifest-media-type', evidence.sourceManifestMediaType],
    ['fraud-detection-platform:source-manifest-content-digest', evidence.sourceManifestContentDigest],
    ['fraud-detection-platform:platform-manifest-digest', evidence.platformManifestDigest],
    ['fraud-detection-platform:platform-manifest-content-digest', evidence.platformManifestContentDigest],
    ['fraud-detection-platform:platform-config-digest', evidence.platformConfigDigest],
  ].map(([name, value]) => ({ name, value }));
}

function baselineFixture() {
  const buildxMetadata = {
    'containerimage.digest': buildxManifestDigest,
    'containerimage.config.digest': imageIdDigest,
    'containerimage.descriptor': {
      digest: buildxManifestDigest,
      mediaType: buildxMediaType,
      size: 321,
      annotations: { 'config.digest': imageIdDigest },
    },
  };
  const evidence = {
    schemaVersion: 2,
    testedGitSha,
    imageRef,
    imagePlatform: 'linux/amd64',
    scannedImageDigest: imageIdDigest,
    scannedImageIdentitySource: 'docker-image-id',
    imageIdDigest,
    dockerImageIdKind: 'docker-image-id',
    repoDigests: [],
    sourceImageDigest: buildxManifestDigest,
    sourceManifestMediaType: buildxMediaType,
    sourceManifestContentDigest: buildxManifestDigest,
    platformManifestDigest: buildxManifestDigest,
    platformManifestMediaType: buildxMediaType,
    platformManifestContentDigest: buildxManifestDigest,
    platformConfigDigest: imageIdDigest,
    buildxActionDigest: buildxManifestDigest,
    buildxDigest: buildxManifestDigest,
    buildxConfigDigest: imageIdDigest,
  };
  const raw = rawSbom();
  const bound = structuredClone(raw);
  bound.metadata.properties.push(...derivedIdentityProperties(evidence));
  return {
    inventory: {
      schemaVersion: 1,
      testedGitSha,
      requiredScanJobs: ['backend'],
      expectedArtifacts: [{
        artifactName,
        logicalName: 'alert-service',
        imageRef,
        expectedPlatform: 'linux/amd64',
        source: 'buildx-loaded-image',
      }],
    },
    evidence,
    result: {
      ...structuredClone(evidence),
      scannerInputImageId: imageIdDigest,
      artifactSha256: {},
      scanner: { name: 'trivy', version: 'v0.76.2', outcome: 'success', policy: 'HIGH,CRITICAL' },
      sbom: { format: 'CycloneDX JSON', generator: 'syft', version: 'v1.57.2', outcome: 'success', bindingOutcome: 'success' },
    },
    buildxMetadata,
    registryIdentity: null,
    trivy: {
      SchemaVersion: 2,
      ArtifactName: imageIdDigest,
      Metadata: { ImageID: imageIdDigest },
      Results: [],
    },
    rawSbom: raw,
    boundSbom: bound,
  };
}

function useRegistryImage(fixture, sourceIsIndex = true) {
  fixture.buildxMetadata = null;
  const platformBytes = jsonBytes({
    schemaVersion: 2,
    mediaType: registryManifestMediaType,
    config: { mediaType: 'application/vnd.oci.image.config.v1+json', digest: imageIdDigest, size: 123 },
    layers: [],
  });
  const selectedDigest = digest(platformBytes);
  const sourceValue = sourceIsIndex
    ? {
      schemaVersion: 2,
      mediaType: registryIndexMediaType,
      manifests: [{
        mediaType: registryManifestMediaType,
        digest: selectedDigest,
        size: platformBytes.length,
        platform: { architecture: 'amd64', os: 'linux' },
      }],
    }
    : JSON.parse(platformBytes.toString('utf8'));
  const sourceBytes = sourceIsIndex ? jsonBytes(sourceValue) : platformBytes;
  const sourceDigest = digest(sourceBytes);
  const repository = 'registry.example/security/image:1';
  const registryImageRef = `${repository}@${sourceDigest}`;
  fixture.inventory.expectedArtifacts[0] = {
    ...fixture.inventory.expectedArtifacts[0],
    imageRef: registryImageRef,
    source: 'registry-pinned',
  };
  fixture.evidence.imageRef = registryImageRef;
  Object.assign(fixture.evidence, {
    dockerImageIdKind: 'platform-config',
    repoDigests: [`${repository}@${sourceDigest}`],
    sourceImageDigest: sourceDigest,
    sourceManifestMediaType: sourceIsIndex ? registryIndexMediaType : registryManifestMediaType,
    sourceManifestContentDigest: sourceDigest,
    platformManifestDigest: sourceIsIndex ? selectedDigest : sourceDigest,
    platformManifestMediaType: registryManifestMediaType,
    platformManifestContentDigest: sourceIsIndex ? selectedDigest : sourceDigest,
    platformConfigDigest: imageIdDigest,
    buildxActionDigest: null,
    buildxDigest: null,
    buildxConfigDigest: null,
  });
  fixture.result = {
    ...fixture.result,
    ...structuredClone(fixture.evidence),
    scannerInputImageId: imageIdDigest,
  };
  fixture.registryIdentity = {
    repository,
    expectedPlatform: 'linux/amd64',
    sourceManifestRawBase64: sourceBytes.toString('base64'),
    sourceImageDigest: sourceDigest,
    sourceManifestMediaType: sourceIsIndex ? registryIndexMediaType : registryManifestMediaType,
    sourceManifestContentDigest: sourceDigest,
    platformManifestRawBase64: platformBytes.toString('base64'),
    platformManifestDigest: sourceIsIndex ? selectedDigest : sourceDigest,
    platformManifestMediaType: registryManifestMediaType,
    platformManifestContentDigest: sourceIsIndex ? selectedDigest : sourceDigest,
    platformConfigDigest: imageIdDigest,
    dockerImageIdKind: 'platform-config',
  };
  fixture.boundSbom = structuredClone(fixture.rawSbom);
  fixture.boundSbom.metadata.properties.push(...derivedIdentityProperties(fixture.evidence));
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
    if (fixture.buildxMetadata !== null) {
      writeFileSync(join(artifactDirectory, 'buildx-metadata.json'), `${JSON.stringify(fixture.buildxMetadata)}\n`);
    }
    if (fixture.registryIdentity) {
      writeFileSync(join(artifactDirectory, 'registry-image-identity.json'), `${JSON.stringify(fixture.registryIdentity)}\n`);
    }
    if (fixture.trivy !== null) writeFileSync(join(artifactDirectory, 'trivy-image.json'), `${JSON.stringify(fixture.trivy)}\n`);
    if (fixture.rawSbom !== null) writeFileSync(join(artifactDirectory, 'image.cdx.raw.json'), `${JSON.stringify(fixture.rawSbom)}\n`);
    if (fixture.boundSbom !== null) writeFileSync(join(artifactDirectory, 'image.cdx.json'), `${JSON.stringify(fixture.boundSbom)}\n`);
    if (fixture.trivy === 'invalid-json') writeFileSync(join(artifactDirectory, 'trivy-image.json'), '{');
    if (fixture.omitArtifact) rmSync(artifactDirectory, { recursive: true, force: true });

    if (!fixture.omitArtifact && fixture.trivy !== null && fixture.trivy !== 'invalid-json') {
      fixture.result.artifactSha256 = {
        evidence: digestFile(join(artifactDirectory, 'image-security-evidence.json')),
        trivy: digestFile(join(artifactDirectory, 'trivy-image.json')),
        sbomRaw: digestFile(join(artifactDirectory, 'image.cdx.raw.json')),
        sbomBound: digestFile(join(artifactDirectory, 'image.cdx.json')),
        buildxMetadata: fixture.buildxMetadata === null ? null : digestFile(join(artifactDirectory, 'buildx-metadata.json')),
        registryIdentity: fixture.registryIdentity === null ? null : digestFile(join(artifactDirectory, 'registry-image-identity.json')),
      };
      if (fixture.resultHashMutation) fixture.result.artifactSha256 = fixture.resultHashMutation(fixture.result.artifactSha256);
      writeFileSync(join(artifactDirectory, 'image-scan-result.json'), `${JSON.stringify(fixture.result)}\n`);
    }

    return spawnSync(process.execPath, [validator, inventoryPath, join(root, 'downloads')], {
      encoding: 'utf8',
      env: {
        ...process.env,
        TESTED_GIT_SHA: testedGitSha,
        IMAGE_SCAN_JOB_RESULTS: JSON.stringify(fixture.jobResults ?? { backend: 'success' }),
      },
    });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function digestFile(file) {
  return createHash('sha256').update(requireFile(file)).digest('hex');
}

function requireFile(file) {
  return readFileSync(file);
}

test('accepts complete Buildx metadata, immutable scanner input, and raw/bound SBOM evidence', () => {
  const result = runValidator();
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('accepts a pinned OCI index with a hashed platform manifest and matching config', () => {
  const result = runValidator((fixture) => useRegistryImage(fixture, true));
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('accepts a direct pinned platform manifest', () => {
  const result = runValidator((fixture) => useRegistryImage(fixture, false));
  assert.equal(result.status, 0, result.stderr || result.stdout);
});

test('rejects a missing artifact directory', () => {
  const result = runValidator((fixture) => { fixture.omitArtifact = true; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /missing/i);
});

test('rejects an unexpected artifact directory', () => {
  const result = runValidator((_fixture, artifactDirectory) => mkdirSync(join(dirname(artifactDirectory), 'unexpected')));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unexpected/i);
});

test('rejects a duplicate inventory artifact name', () => {
  const result = runValidator((fixture) => fixture.inventory.expectedArtifacts.push({ ...fixture.inventory.expectedArtifacts[0] }));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /more than once/i);
});

test('rejects malformed Trivy JSON', () => {
  const result = runValidator((fixture) => { fixture.trivy = 'invalid-json'; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /invalid JSON/i);
});

test('rejects a Trivy report whose Docker image ID differs from the immutable scanner input', () => {
  const result = runValidator((fixture) => { fixture.trivy.Metadata.ImageID = platformManifestDigest; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /captured Docker image config digest/i);
});

test('rejects a Trivy report for a different immutable image reference', () => {
  const result = runValidator((fixture) => { fixture.trivy.ArtifactName = 'registry.example/unexpected@sha256:abc'; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /immutable Docker image ID/i);
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

test('rejects a CRITICAL vulnerability finding', () => {
  const result = runValidator((fixture) => {
    fixture.trivy.Results = [{ Vulnerabilities: [{ Severity: 'CRITICAL', VulnerabilityID: 'TEST-CRITICAL', PkgName: 'fixture' }] }];
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /reports CRITICAL/i);
});

test('rejects stale revision evidence', () => {
  const result = runValidator((fixture) => { fixture.evidence.testedGitSha = 'b'.repeat(40); });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /different Git SHA/i);
});

test('rejects a changed Buildx descriptor to manifest relation', () => {
  const result = runValidator((fixture) => {
    fixture.buildxMetadata['containerimage.descriptor'].digest = platformManifestDigest;
    assert.notEqual(fixture.buildxMetadata['containerimage.descriptor'].digest, fixture.evidence.buildxDigest);
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Buildx descriptor does not bind/i);
});

test('rejects a changed Buildx config relation even when evidence copies agree', () => {
  const result = runValidator((fixture) => {
    const wrongConfig = `sha256:${'4'.repeat(64)}`;
    fixture.buildxMetadata['containerimage.config.digest'] = wrongConfig;
    fixture.buildxMetadata['containerimage.descriptor'].annotations['config.digest'] = wrongConfig;
    assert.notEqual(wrongConfig, fixture.evidence.imageIdDigest);
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Buildx action output differs|loaded Docker image ID does not match Buildx/i);
});

test('rejects missing Buildx exporter metadata', () => {
  const result = runValidator((fixture) => { fixture.buildxMetadata = null; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /buildx-metadata\.json is missing/i);
});

test('rejects coordinated evidence and result mutation when exporter metadata disagrees', () => {
  const result = runValidator((fixture) => {
    const wrongImageId = `sha256:${'5'.repeat(64)}`;
    fixture.evidence.imageIdDigest = wrongImageId;
    fixture.evidence.scannedImageDigest = wrongImageId;
    fixture.evidence.platformConfigDigest = wrongImageId;
    fixture.evidence.buildxConfigDigest = wrongImageId;
    Object.assign(fixture.result, {
      imageIdDigest: wrongImageId,
      scannedImageDigest: wrongImageId,
      platformConfigDigest: wrongImageId,
      buildxConfigDigest: wrongImageId,
      scannerInputImageId: wrongImageId,
    });
    fixture.boundSbom = structuredClone(fixture.rawSbom);
    fixture.boundSbom.metadata.properties.push(...derivedIdentityProperties(fixture.evidence));
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /loaded Docker image ID does not match Buildx/i);
});

test('rejects a bound SBOM whose image ID differs from its raw source and evidence', () => {
  const result = runValidator((fixture) => {
    const property = fixture.boundSbom.metadata.properties.find((entry) => entry.name === 'fraud-detection-platform:image-id');
    property.value = platformManifestDigest;
    assert.notEqual(property.value, fixture.evidence.imageIdDigest);
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /SBOM identity binding fraud-detection-platform:image-id/i);
});

test('rejects a raw SBOM that already contains derived identity metadata', () => {
  const result = runValidator((fixture) => {
    fixture.rawSbom.metadata.properties.push({ name: 'fraud-detection-platform:image-id', value: imageIdDigest });
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /raw SBOM already contains derived identity properties/i);
});

test('rejects a missing Trivy scan report', () => {
  const result = runValidator((fixture) => { fixture.trivy = null; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /trivy-image\.json is missing/i);
});

test('rejects a cancelled source job even when evidence files exist', () => {
  const result = runValidator((fixture) => { fixture.jobResults = { backend: 'cancelled' }; });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /status cancelled/i);
});

test('rejects registry evidence from another repository', () => {
  const result = runValidator((fixture) => {
    useRegistryImage(fixture, true);
    fixture.registryIdentity.repository = 'registry.example/other/image:1';
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /another repository or platform/i);
});

test('rejects a wrong registry source manifest digest', () => {
  const result = runValidator((fixture) => {
    useRegistryImage(fixture, true);
    fixture.inventory.expectedArtifacts[0].imageRef = `registry.example/security/image:1@${platformManifestDigest}`;
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /raw manifest hash does not match/i);
});

test('rejects a wrong registry platform manifest digest', () => {
  const result = runValidator((fixture) => {
    useRegistryImage(fixture, true);
    fixture.registryIdentity.platformManifestDigest = platformManifestDigest;
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /platform raw manifest hash does not match/i);
});

test('rejects a wrong registry platform config', () => {
  const result = runValidator((fixture) => {
    useRegistryImage(fixture, true);
    fixture.registryIdentity.platformConfigDigest = platformManifestDigest;
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /loaded Docker image ID does not match/i);
});

test('rejects a result that drops an independently recorded evidence hash', () => {
  const result = runValidator((fixture) => {
    fixture.resultHashMutation = (hashes) => ({ ...hashes, evidence: '0'.repeat(64) });
  });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /SHA-256 evidence for evidence is missing or incorrect/i);
});
