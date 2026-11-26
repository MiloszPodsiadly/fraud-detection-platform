import assert from 'node:assert/strict';
import test from 'node:test';
import { verifyNpmSbomToolchainEvidence } from './verify-npm-sbom-toolchain.mjs';

function fixture() {
  return {
    packageJson: { dependencies: { ajv: '8.20.0' } },
    lockfile: {
      packages: {
        '': { dependencies: { ajv: '8.20.0' } },
        'node_modules/ajv': { version: '8.20.0' },
        'node_modules/parent/node_modules/ajv': { version: '8.18.0' },
      },
    },
    audit: {
      auditReportVersion: 2,
      vulnerabilities: {},
      metadata: {
        vulnerabilities: { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 },
        dependencies: { total: 2 },
      },
    },
  };
}

test('accepts a patched complete AJV lock and clean audit matching its package count', () => {
  const evidence = fixture();
  assert.equal(verifyNpmSbomToolchainEvidence(
    evidence.packageJson, evidence.lockfile, evidence.audit,
  ).lockedPackageCount, 2);
});

test('rejects vulnerable or prerelease AJV installations and a missing AJV package', () => {
  const evidence = fixture();
  evidence.lockfile.packages['node_modules/parent/node_modules/ajv'].version = '8.17.1';
  assert.throws(() => verifyNpmSbomToolchainEvidence(
    evidence.packageJson, evidence.lockfile, evidence.audit,
  ), /unpatched AJV/);

  const prerelease = fixture();
  prerelease.lockfile.packages['node_modules/ajv'].version = '8.20.0-rc.1';
  assert.throws(() => verifyNpmSbomToolchainEvidence(
    prerelease.packageJson, prerelease.lockfile, prerelease.audit,
  ), /unpatched AJV/);

  const missing = fixture();
  delete missing.lockfile.packages['node_modules/ajv'];
  delete missing.lockfile.packages['node_modules/parent/node_modules/ajv'];
  assert.throws(() => verifyNpmSbomToolchainEvidence(
    missing.packageJson, missing.lockfile, missing.audit,
  ), /unpatched AJV/);
});

test('rejects vulnerable, incomplete, or stale npm audit evidence', () => {
  for (const mutate of [
    (audit) => { audit.metadata.vulnerabilities.high = 1; audit.metadata.vulnerabilities.total = 1; },
    (audit) => { audit.metadata.vulnerabilities.low = undefined; },
    (audit) => { audit.metadata.dependencies.total += 1; },
    (audit) => { audit.vulnerabilities.ajv = { severity: 'high' }; },
  ]) {
    const evidence = fixture();
    mutate(evidence.audit);
    assert.throws(() => verifyNpmSbomToolchainEvidence(
      evidence.packageJson, evidence.lockfile, evidence.audit,
    ), /audit report/);
  }
});
