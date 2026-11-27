import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { SECURITY_POLICY, securityGatePhase, securityGatePhases, validateSecurityPolicy, writeSecurityPolicyOutputs } from "./run-security-gate.mjs";

test("exposes known bounded phases for independent CI producers", () => {
  assert.ok(securityGatePhases().includes("image-inventory"));
  assert.ok(securityGatePhases().includes("maven-prefetch"));
  assert.ok(securityGatePhases().includes("sbom-validation"));
  assert.ok(securityGatePhases().includes("pip-audit-install"));
  assert.ok(securityGatePhases().includes("pip-dependency-audit"));
  assert.ok(securityGatePhases().includes("npm-sbom-audit"));
  assert.ok(securityGatePhases().includes("platform-security-evidence"));
  assert.equal(securityGatePhase("not-a-phase"), null);
  assert.match(securityGatePhase("secret-proof").file, /scripts[\\/]security[\\/]scanners[\\/]verify-trivy-secret-only\.mjs$/);
  assert.match(securityGatePhase("npm-sbom-audit").file, /scripts[\\/]security[\\/]sbom[\\/]run-npm-sbom-toolchain-audit\.mjs$/);
});

test("central policy provides the pinned versions, severity policy, platforms, and artifact retention", () => {
  assert.equal(SECURITY_POLICY.tools.trivy, "0.76.2");
  assert.equal(SECURITY_POLICY.tools.syft, "1.57.2");
  assert.equal(SECURITY_POLICY.tools.pipAudit, "2.10.1");
  assert.deepEqual(SECURITY_POLICY.vulnerabilityPolicy.blockingSeverities, ["HIGH", "CRITICAL"]);
  assert.equal(SECURITY_POLICY.platforms.image, "linux/amd64");
  assert.equal(SECURITY_POLICY.formats.imageSbom, "CycloneDX JSON");
  assert.deepEqual(SECURITY_POLICY.imageIdentity.allowedSources, ["buildx-loaded-image", "registry-pinned"]);
  assert.equal(SECURITY_POLICY.artifacts.imageEvidenceFiles.evidence, "image-security-evidence.json");
  assert.equal(SECURITY_POLICY.platformGate.artifactName, "platform-security-evidence");
  assert.deepEqual(SECURITY_POLICY.platformGate.imageValidationJobs.inventory, ["backend-images", "service-images", "infrastructure-images"]);
  assert.equal(SECURITY_POLICY.artifacts.retentionDays, 30);
});

test("central policy rejects duplicate producers, invalid blocking severities, and malformed evidence names", () => {
  const duplicateProducer = structuredClone(SECURITY_POLICY);
  duplicateProducer.platformGate.requiredProducerJobs.push(duplicateProducer.platformGate.requiredProducerJobs[0]);
  assert.throws(() => validateSecurityPolicy(duplicateProducer), /Central security policy is malformed/);

  const unreportedSeverity = structuredClone(SECURITY_POLICY);
  unreportedSeverity.vulnerabilityPolicy.blockingSeverities = ["CRITICAL", "INFO"];
  assert.throws(() => validateSecurityPolicy(unreportedSeverity), /Central security policy is malformed/);

  const unsafeFileName = structuredClone(SECURITY_POLICY);
  unsafeFileName.platformGate.requiredFiles.secretProof = "../secret.json";
  assert.throws(() => validateSecurityPolicy(unsafeFileName), /Central security policy is malformed/);
});

test("writes GitHub outputs from policy without duplicated YAML values", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "fdp157-policy-test-"));
  const output = path.join(directory, "github-output.txt");
  try {
    writeSecurityPolicyOutputs(output);
    const value = fs.readFileSync(output, "utf8");
    assert.match(value, /trivy_version=v0\.76\.2/);
  assert.match(value, /pip_audit_version=2\.10\.1/);
    assert.match(value, /all_vulnerability_severities=UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL/);
    assert.match(value, /blocking_vulnerability_severities=HIGH,CRITICAL/);
    assert.match(value, /artifact_retention_days=30/);
    assert.match(value, /image_evidence_file=image-security-evidence\.json/);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

test("the Platform Security Gate waits for every centrally required same-run producer", () => {
  const ci = fs.readFileSync(new URL("../../.github/workflows/ci.yml", import.meta.url), "utf8").replace(/\r\n/g, "\n");
  const start = ci.indexOf("\n  platform-security-gate:\n");
  assert.notEqual(start, -1);
  const tail = ci.slice(start + 1);
  const section = tail.slice(0, tail.search(/\n  [a-z0-9_-]+:\n/) < 0 ? undefined : tail.search(/\n  [a-z0-9_-]+:\n/));
  const needs = section.match(/^      - ([a-z0-9_-]+)$/gm)?.map((line) => line.slice(8)) ?? [];
  assert.deepEqual(needs, SECURITY_POLICY.platformGate.requiredProducerJobs);
  assert.match(fs.readFileSync(new URL("../../.github/workflows/docker-compose-ci.yml", import.meta.url), "utf8"), /\non:\r?\n  workflow_call:/);
  assert.match(fs.readFileSync(new URL("../../.github/workflows/sbom.yml", import.meta.url), "utf8"), /\non:\r?\n  workflow_call:/);
});
