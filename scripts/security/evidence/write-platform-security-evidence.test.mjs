import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { SECURITY_POLICY } from '../config/policy.mjs';
import { IMAGE_SECURITY_INVENTORY } from '../config/image-inventory.mjs';
import { buildPlatformSecurityEvidence } from './write-platform-security-evidence.mjs';

const sha = 'a'.repeat(40);

test('writes a pass decision only with every required producer and same-run evidence artifact', () => {
  const fixture = createEvidenceFixture();
  try {
    const evidence = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: sha });
    assert.equal(evidence.finalDecision, 'PASS');
    assert.deepEqual(evidence.failures, []);
    assert.equal(evidence.producerOutcomes['compose-ci'], 'success');
    assert.equal(evidence.imageEvidence.reduce((total, summary) => total + summary.artifactCount, 0), 30);
    assert.ok(evidence.artifactReferences.every((artifact) => artifact.files.every((file) => /^[a-f0-9]{64}$/.test(file.sha256))));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('rejects missing, failed, skipped, and cancelled producer outcomes', () => {
  const fixture = createEvidenceFixture();
  try {
    for (const result of ['failure', 'skipped', 'cancelled']) {
      const needs = { ...fixture.input.needs, backend: { result } };
      const evidence = buildPlatformSecurityEvidence({ ...fixture.input, needs, testedGitSha: sha });
      assert.equal(evidence.finalDecision, 'FAIL');
      assert.ok(evidence.failures.some((failure) => failure.includes(`backend completed with ${result}`)));
    }
    const needs = { ...fixture.input.needs };
    delete needs['resolved-sbom'];
    const missing = buildPlatformSecurityEvidence({ ...fixture.input, needs, testedGitSha: sha });
    assert.ok(missing.failures.some((failure) => failure.includes('Required producer result is missing: resolved-sbom')));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('rejects evidence from a stale revision and incomplete artifact inventory', () => {
  const fixture = createEvidenceFixture();
  try {
    const stale = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: 'b'.repeat(40) });
    assert.equal(stale.finalDecision, 'FAIL');
    assert.ok(stale.failures.some((failure) => failure.includes('PR merge revision')));
    assert.ok(stale.failures.some((failure) => failure.includes('resolved-cyclonedx-sbom-b')));

    const expectedImage = path.join(fixture.root, `${IMAGE_SECURITY_INVENTORY.groups.backend[0].artifactName}-${sha}`);
    fs.rmSync(expectedImage, { recursive: true, force: true });
    const incomplete = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: sha });
    assert.ok(incomplete.failures.some((failure) => failure.includes(path.basename(expectedImage))));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('rejects an image validation summary that omits required scan jobs', () => {
  const fixture = createEvidenceFixture();
  try {
    const summaryPath = path.join(fixture.root, `image-scan-validation-${sha}`, 'validated-image-scan-summary.json');
    const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
    summary.requiredScanJobs = {};
    fs.writeFileSync(summaryPath, `${JSON.stringify(summary)}\n`);
    const evidence = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: sha });
    assert.equal(evidence.finalDecision, 'FAIL');
    assert.ok(evidence.failures.some((failure) => failure.includes('image-scan-validation-')
      && failure.includes('complete successful image evidence')));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('rejects ambiguous duplicate evidence files within an artifact', () => {
  const fixture = createEvidenceFixture();
  try {
    const artifact = path.join(fixture.root, `pip-audit-${sha}`);
    const duplicate = path.join(artifact, 'nested', 'pip-audit.json');
    fs.mkdirSync(path.dirname(duplicate), { recursive: true });
    fs.copyFileSync(path.join(artifact, 'pip-audit.json'), duplicate);
    const evidence = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: sha });
    assert.equal(evidence.finalDecision, 'FAIL');
    assert.ok(evidence.failures.some((failure) => failure.includes('pip-audit.json appears more than once')));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('keeps a readable failure contract when a Trivy source report is malformed', () => {
  const fixture = createEvidenceFixture();
  try {
    const reportPath = path.join(fixture.root, `repository-vulnerability-evidence-${sha}`, 'repository.json');
    fs.writeFileSync(reportPath, JSON.stringify({ SchemaVersion: 2, Results: [] }));
    const evidence = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: sha });
    assert.equal(evidence.finalDecision, 'FAIL');
    assert.ok(evidence.failures.some((failure) => failure.includes('Trivy vulnerability source report failed validation')));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

test('recomputes dependency vulnerability decisions from the downloaded reports', () => {
  const fixture = createEvidenceFixture();
  try {
    const npmPath = path.join(fixture.root, `npm-audit-${sha}`, 'npm-audit.json');
    const npm = JSON.parse(fs.readFileSync(npmPath, 'utf8'));
    npm.metadata.vulnerabilities.high = 1;
    npm.metadata.vulnerabilities.total = 1;
    fs.writeFileSync(npmPath, `${JSON.stringify(npm)}\n`);
    const evidence = buildPlatformSecurityEvidence({ ...fixture.input, testedGitSha: sha });
    assert.equal(evidence.finalDecision, 'FAIL');
    assert.ok(evidence.failures.some((failure) => failure.includes('npm audit report contains blocking vulnerabilities')));
  } finally {
    fs.rmSync(fixture.root, { recursive: true, force: true });
  }
});

function createEvidenceFixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fdp157-platform-security-evidence-'));
  const requiredArtifactNames = SECURITY_POLICY.platformGate.requiredArtifactBases.map((base) => `${base}-${sha}`);
  const imageArtifactNames = [
    ...Object.values(IMAGE_SECURITY_INVENTORY.groups).flat().map((entry) => `${entry.artifactName}-${sha}`),
    ...SECURITY_POLICY.platformGate.historicalImageArtifacts.map((name) => `${name}-${sha}`),
  ];
  for (const name of [...requiredArtifactNames, ...imageArtifactNames]) fs.mkdirSync(path.join(root, name), { recursive: true });

  writeArtifactJson(root, `npm-audit-${sha}`, 'npm-audit.json', {
    metadata: { vulnerabilities: { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 } },
  });
  writeArtifactJson(root, `pip-audit-${sha}`, 'pip-audit.json', {
    dependencies: [{ name: 'locked-package', version: '1.0.0', vulns: [] }], fixes: [],
  });
  writeArtifactJson(root, `repository-config-scan-${sha}`, 'trivy-config.json', { SchemaVersion: 2, Results: [] });
  writeArtifactJson(root, `repository-secret-proof-${sha}`, 'secret-proof-summary.json', {
    testedGitSha: sha,
    trivyVersion: SECURITY_POLICY.tools.trivy,
    offlineSecretOnlyScan: 'success',
    expectedRuleId: SECURITY_POLICY.secretProof.expectedRuleId,
    expectedSeverity: SECURITY_POLICY.secretProof.expectedSeverity,
    findingCount: SECURITY_POLICY.secretProof.expectedFindingCount,
    cleanControl: 'passed',
    independentOfflineMavenFailure: 'passed',
  });
  const repositoryReport = trivyReport('repository source');
  const mavenReport = trivyReport('resolved Maven source');
  const combinedReport = {
    SchemaVersion: 2,
    Trivy: { Version: SECURITY_POLICY.tools.trivy },
    ArtifactName: 'fraud-detection-platform repository and resolved Maven artifacts',
    ArtifactType: 'filesystem',
    Results: [...repositoryReport.Results, ...mavenReport.Results],
  };
  const combinedBytes = `${JSON.stringify(combinedReport, null, 2)}\n`;
  const reportSummary = {
    trivyVersion: SECURITY_POLICY.tools.trivy,
    policy: {
      blockingSeverities: [...SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities],
      visibleSeverities: [...SECURITY_POLICY.vulnerabilityPolicy.visibleSeverities],
    },
    decision: 'PASS',
    countsBySeverity: Object.fromEntries(SECURITY_POLICY.vulnerabilityPolicy.visibleSeverities.map((severity) => [severity, 0])),
    totalVulnerabilities: 0,
    blockingVulnerabilities: 0,
    sources: [
      sourceSummary('repository', repositoryReport),
      sourceSummary('resolved-maven-artifacts', mavenReport),
    ],
    combinedReportSha256: createHash('sha256').update(combinedBytes).digest('hex'),
  };
  writeArtifactJson(root, `repository-vulnerability-evidence-${sha}`, 'repository.json', repositoryReport);
  writeArtifactJson(root, `repository-vulnerability-evidence-${sha}`, 'resolved-maven.json', mavenReport);
  writeArtifactJson(root, `repository-vulnerability-evidence-${sha}`, 'summary.json', reportSummary);
  writeArtifactText(root, `repository-vulnerability-evidence-${sha}`, 'combined.json', combinedBytes);
  for (const file of SECURITY_POLICY.platformGate.requiredFiles.sbomDocuments) {
    writeArtifactJson(root, `resolved-cyclonedx-sbom-${sha}`, file, { bomFormat: 'CycloneDX' });
  }
  const inventoryCount = Object.values(IMAGE_SECURITY_INVENTORY.groups).flat().length;
  writeImageSummary(root, `image-scan-validation-${sha}`, inventoryCount, {
    'backend-images': 'success', 'service-images': 'success', 'infrastructure-images': 'success',
  });
  writeImageSummary(root, `fdp-image-scan-validation-${sha}`, 4, {
    fdp37: 'success', fdp38: 'success', fdp39: 'success',
  });
  for (const artifactName of imageArtifactNames) {
    for (const file of [
      SECURITY_POLICY.artifacts.imageEvidenceFiles.evidence,
      SECURITY_POLICY.artifacts.imageEvidenceFiles.dockerInspect,
      SECURITY_POLICY.artifacts.imageEvidenceFiles.dockerInspectAfterScan,
      SECURITY_POLICY.artifacts.imageEvidenceFiles.scanResult,
      SECURITY_POLICY.artifacts.imageEvidenceFiles.trivy,
      SECURITY_POLICY.artifacts.imageEvidenceFiles.rawSbom,
      SECURITY_POLICY.artifacts.imageEvidenceFiles.boundSbom,
    ]) {
      writeArtifactJson(root, artifactName, file, {});
    }
  }

  const needs = Object.fromEntries(SECURITY_POLICY.platformGate.requiredProducerJobs.map((job) => [job, { result: 'success' }]));
  return {
    root,
    input: {
      artifactDirectory: root,
      needs,
      testedGitSha: sha,
      prHeadSha: 'b'.repeat(40),
      prMergeSha: sha,
      runId: '12345',
      runAttempt: '2',
      repository: 'example/fraud-detection-platform',
      ref: 'refs/pull/157/merge',
      workflow: 'CI',
      eventName: 'pull_request',
    },
  };
}

function writeImageSummary(root, artifactName, count, requiredScanJobs) {
  writeArtifactJson(root, artifactName, 'validated-image-scan-summary.json', {
    schemaVersion: 2,
    outcome: 'success',
    testedGitSha: sha,
    expectedArtifactCount: count,
    artifactCount: count,
    requiredScanJobs,
  });
}

function writeArtifactJson(root, artifactName, fileName, value) {
  const directory = path.join(root, artifactName);
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, fileName), `${JSON.stringify(value)}\n`);
}

function writeArtifactText(root, artifactName, fileName, contents) {
  const directory = path.join(root, artifactName);
  fs.mkdirSync(directory, { recursive: true });
  fs.writeFileSync(path.join(directory, fileName), contents);
}

function trivyReport(artifactName) {
  return {
    SchemaVersion: 2,
    Trivy: { Version: SECURITY_POLICY.tools.trivy },
    ArtifactName: artifactName,
    ArtifactType: 'filesystem',
    Results: [{
      Target: artifactName,
      Packages: [{ Name: 'locked-package', Version: '1.0.0' }],
      Vulnerabilities: [],
    }],
  };
}

function sourceSummary(name, report) {
  return {
    name,
    artifactName: report.ArtifactName,
    targetCount: report.Results.length,
    packageCount: report.Results.reduce((count, result) => count + result.Packages.length, 0),
    vulnerabilityCount: report.Results.reduce((count, result) => count + (result.Vulnerabilities?.length ?? 0), 0),
    reportSha256: createHash('sha256').update(JSON.stringify(report)).digest('hex'),
  };
}
