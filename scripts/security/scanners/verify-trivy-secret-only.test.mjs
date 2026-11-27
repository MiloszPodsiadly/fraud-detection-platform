import assert from "node:assert/strict";
import test from "node:test";
import { SECURITY_POLICY } from "../config/policy.mjs";
import {
  parseSecretProbeReport,
  SYNTHETIC_AWS_ACCESS_KEY_ID,
  validateSecretProbeReport,
} from "./verify-trivy-secret-only.mjs";

const expectedFinding = {
  RuleID: SECURITY_POLICY.secretProof.expectedRuleId,
  Severity: SECURITY_POLICY.secretProof.expectedSeverity,
};

function report(results = []) {
  return { SchemaVersion: 2, Results: results };
}

test("uses the documented nonfunctional AWS sample marker", () => {
  assert.equal(SYNTHETIC_AWS_ACCESS_KEY_ID, "AKIAIOSFODNN7EXAMPLE");
});

test("accepts exactly one critical AWS key finding in the fixture and no clean-control findings", () => {
  assert.deepEqual(validateSecretProbeReport(report([
    { Target: "SyntheticCredentialFixture.java", Secrets: [expectedFinding] },
    { Target: "CleanControl.java", Secrets: [] },
  ])), {
    findingCount: SECURITY_POLICY.secretProof.expectedFindingCount,
    ruleId: SECURITY_POLICY.secretProof.expectedRuleId,
    severity: SECURITY_POLICY.secretProof.expectedSeverity,
    target: SECURITY_POLICY.secretProof.expectedTarget,
  });
});

test("rejects malformed JSON and malformed report structure", () => {
  assert.throws(() => parseSecretProbeReport("{not-json"), /malformed JSON evidence/);
  assert.throws(() => validateSecretProbeReport({ SchemaVersion: 2 }), /malformed JSON evidence/);
  assert.throws(() => validateSecretProbeReport(report([{ Target: "x.java", Secrets: {} }])), /malformed JSON evidence/);
});

test("fails closed when the positive control has no findings", () => {
  assert.throws(() => validateSecretProbeReport(report([
    { Target: "SyntheticCredentialFixture.java", Secrets: [] },
    { Target: "CleanControl.java", Secrets: [] },
  ])), /exactly one critical AWS key-rule finding/);
});

test("rejects a finding attached to the wrong file or rule", () => {
  assert.throws(() => validateSecretProbeReport(report([
    { Target: "Other.java", Secrets: [expectedFinding] },
  ])), /exactly one critical AWS key-rule finding/);
  assert.throws(() => validateSecretProbeReport(report([
    { Target: "SyntheticCredentialFixture.java", Secrets: [{ ...expectedFinding, RuleID: "other-rule" }] },
  ])), /exactly one critical AWS key-rule finding/);
});

test("rejects unexpected extra findings and any finding in the clean control", () => {
  assert.throws(() => validateSecretProbeReport(report([
    { Target: "SyntheticCredentialFixture.java", Secrets: [expectedFinding] },
    { Target: "CleanControl.java", Secrets: [{ RuleID: "other-rule", Severity: "LOW" }] },
  ])), /exactly one critical AWS key-rule finding/);
});

test("rejects a noncritical positive control", () => {
  assert.throws(() => validateSecretProbeReport(report([
    { Target: "SyntheticCredentialFixture.java", Secrets: [{ ...expectedFinding, Severity: "HIGH" }] },
  ])), /exactly one critical AWS key-rule finding/);
});
