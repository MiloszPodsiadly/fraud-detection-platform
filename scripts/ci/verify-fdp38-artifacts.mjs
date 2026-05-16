import {
  assertChecks,
  assertFile,
  assertIncludes,
  assertJUnitClassPassed,
  assertNoRegex,
  assertNoTokensInText,
  readJson,
  readText
} from "./artifact-verification-helpers.mjs";

const summaryMd = "alert-service/target/fdp38-chaos/fdp38-proof-summary.md";
const summaryJson = "alert-service/target/fdp38-chaos/fdp38-proof-summary.json";
const evidenceMd = "alert-service/target/fdp38-chaos/fdp38-live-checkpoint-evidence.md";
const provenanceJson = "alert-service/target/fdp38-chaos/fdp38-fixture-image-provenance.json";

for (const path of [summaryMd, summaryJson, evidenceMd, provenanceJson]) {
  assertFile(path);
}

for (const checkpoint of [
  "after-attempted-audit-before-business-mutation",
  "before-legacy-business-mutation",
  "before-fdp29-local-finalize",
  "before-success-audit-retry"
]) {
  assertFile(`alert-service/target/fdp38-chaos/fdp38-proof-summary-${checkpoint}.md`);
  assertFile(`alert-service/target/fdp38-chaos/fdp38-proof-summary-${checkpoint}.json`);
}

const required = {
  BEFORE_LEGACY_BUSINESS_MUTATION: {
    className: "RegulatedMutationLiveCheckpointBeforeBusinessMutationIT",
    method: "beforeLegacyBusinessMutationLiveKillDoesNotCommitOrPublish",
    precondition_setup: "LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST"
  },
  AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION: {
    className: "RegulatedMutationLiveCheckpointAfterAttemptedAuditIT",
    method: "afterAttemptedAuditBeforeBusinessMutationLiveKillPreservesAttemptedAuditOnly",
    precondition_setup: "LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST"
  },
  BEFORE_FDP29_LOCAL_FINALIZE: {
    className: "RegulatedMutationLiveCheckpointBeforeFdp29FinalizeIT",
    method: "beforeFdp29LocalFinalizeLiveKillDoesNotClaimFinality",
    precondition_setup: "LIVE_HTTP_FLOW_FROM_INITIAL_REQUEST"
  },
  BEFORE_SUCCESS_AUDIT_RETRY: {
    className: "RegulatedMutationLiveCheckpointBeforeSuccessAuditRetryIT",
    method: "beforeSuccessAuditRetryLiveKillDoesNotDuplicateBusinessMutationOrOutbox",
    precondition_setup: "SEEDED_DURABLE_PRECONDITION_THEN_RUNTIME_REACHED_CHECKPOINT"
  }
};

for (const metadata of Object.values(required)) {
  assertJUnitClassPassed({
    className: metadata.className,
    reportPath: `alert-service/target/surefire-reports/TEST-com.frauddetection.alert.regulated.${metadata.className}.xml`,
    label: "FDP-38",
    requireMethod: metadata.method
  });
}

const expectedSha = process.env.GITHUB_SHA ?? "";
const expectedImage = `fdp38-alert-service-test-fixture:${expectedSha}`;
const summary = readJson(summaryJson);
assertChecks("FDP-38 proof summary", {
  commit_sha: summary.commit_sha === expectedSha,
  fixture_image_name: summary.fixture_image_name === expectedImage,
  fixture_image_id: String(summary.fixture_image_id ?? "").startsWith("sha256:"),
  fixture_image_digest_or_id: ![undefined, null, "", "LOCAL_IMAGE_DIGEST_NOT_PROVIDED"].includes(summary.fixture_image_digest_or_id),
  fixture_image_kind: summary.fixture_image_kind === "test-fixture-production-like",
  fixture_image: summary.fixture_image === true,
  release_image: summary.release_image === false,
  contains_test_classes: summary.contains_test_classes === true,
  contains_test_profiles: summary.contains_test_profiles === true,
  release_candidate_allowed: summary.release_candidate_allowed === false,
  production_deployable: summary.production_deployable === false,
  production_enablement: summary.production_enablement === false,
  live_runtime_checkpoint_proof_executed: summary.live_runtime_checkpoint_proof_executed === true,
  proof_levels: String(summary.proof_levels).includes("LIVE_IN_FLIGHT_REQUEST_KILL"),
  state_reach_methods: String(summary.state_reach_methods).includes("RUNTIME_REACHED_TEST_FIXTURE"),
  runtime_reached_production_image: summary.runtime_reached_production_image === false,
  checkpoint_count: Number.parseInt(summary.checkpoint_count ?? "0", 10) === Object.keys(required).length,
  killed_container_id_masked: ![undefined, null, "", "absent"].includes(summary.killed_container_id_masked),
  restarted_container_id_masked: ![undefined, null, "", "absent"].includes(summary.restarted_container_id_masked),
  no_false_success: summary.no_false_success === true,
  no_duplicate_mutation: summary.no_duplicate_mutation === true,
  no_duplicate_outbox: summary.no_duplicate_outbox === true,
  no_duplicate_success_audit: summary.no_duplicate_success_audit === true,
  recovery_wins: summary.recovery_wins === true,
  final_result: summary.final_result === "PASS"
});

const checkpointNames = new Set(summary.checkpoint_names ?? []);
assertChecks("FDP-38 checkpoint registration", Object.fromEntries(Object.keys(required).map((checkpoint) => [checkpoint, checkpointNames.has(checkpoint)])));
assertChecks("FDP-38 checkpoint registration size", { checkpoint_count: checkpointNames.size === Object.keys(required).length });

if ((summary.failed_false_success_reasons ?? []).length !== 0) {
  throw new Error(`FDP-38 false success reasons must be empty: ${summary.failed_false_success_reasons}`);
}

const requiredInvariants = new Set([
  "public_success_status_absent",
  "committed_snapshot_absent_when_not_allowed",
  "finalized_status_absent_when_not_allowed",
  "success_audit_absent_when_not_allowed",
  "outbox_absent_when_not_allowed",
  "business_mutation_absent_when_not_allowed",
  "duplicate_mutation_absent",
  "duplicate_outbox_absent",
  "duplicate_success_audit_absent",
  "passed",
  "failed_reasons"
]);

for (const [checkpoint, metadata] of Object.entries(required)) {
  const precondition = summary.precondition_setup?.[checkpoint];
  if (precondition !== metadata.precondition_setup) {
    throw new Error(`FDP-38 wrong precondition setup for ${checkpoint}: ${precondition}`);
  }
  const evaluation = summary.false_success_evaluation?.[checkpoint];
  if (!evaluation || typeof evaluation !== "object") {
    throw new Error(`FDP-38 missing false success evaluation for ${checkpoint}`);
  }
  const missing = [...requiredInvariants].filter((key) => !(key in evaluation));
  if (missing.length > 0) {
    throw new Error(`FDP-38 false success evaluation missing keys for ${checkpoint}: ${missing.join(", ")}`);
  }
  if (evaluation.passed !== true || (evaluation.failed_reasons ?? []).length !== 0) {
    throw new Error(`FDP-38 false success evaluation failed for ${checkpoint}: ${JSON.stringify(evaluation)}`);
  }
}

const artifactText = `${readText(summaryMd)}\n${readText(evidenceMd)}`;
assertNoTokensInText("FDP-38 artifacts", `${artifactText}\n${JSON.stringify(summary)}`, [
  "PLACEHOLDER",
  "TO_BE_FILLED",
  "LOCAL_IMAGE_ID_NOT_PROVIDED",
  "LOCAL_IMAGE_DIGEST_NOT_PROVIDED"
]);
for (const [checkpoint, metadata] of Object.entries(required)) {
  if (!artifactText.includes(checkpoint) || !artifactText.includes(`precondition_setup=${metadata.precondition_setup}`)) {
    throw new Error(`FDP-38 evidence markdown missing checkpoint/precondition: ${checkpoint}`);
  }
}

const provenance = readJson(provenanceJson);
assertChecks("FDP-38 provenance", {
  release_image: provenance.release_image === false,
  fixture_image: provenance.fixture_image === true,
  contains_test_classes: provenance.contains_test_classes === true,
  contains_test_profiles: provenance.contains_test_profiles === true,
  release_candidate_allowed: provenance.release_candidate_allowed === false,
  production_deployable: provenance.production_deployable === false,
  image_name: String(provenance.image_name ?? "").includes("fdp38-alert-service-test-fixture")
});

const ci = readText(".github/workflows/ci.yml");
const fdp38Start = ci.indexOf("fdp38-live-runtime-checkpoint-chaos:");
const dockerStart = ci.indexOf("\n  docker:", fdp38Start);
const fdp39Start = ci.indexOf("\n  fdp39-release-governance:", fdp38Start);
const fdp38AllowedEnd = fdp39Start >= 0 ? fdp39Start : dockerStart;
for (let index = ci.indexOf("Dockerfile.alert-service-fdp38-fixture"); index >= 0; index = ci.indexOf("Dockerfile.alert-service-fdp38-fixture", index + 1)) {
  const inFdp38 = fdp38Start < index && index < fdp38AllowedEnd;
  const inFdp39 = fdp39Start >= 0 && fdp39Start < index && index < dockerStart;
  if (!inFdp38 && !inFdp39) {
    throw new Error("FDP-38 fixture Dockerfile is referenced outside the FDP-38/FDP-39 CI jobs");
  }
}
if (readText("deployment/docker-compose.yml").includes("Dockerfile.alert-service-fdp38-fixture")) {
  throw new Error("FDP-38 fixture Dockerfile must not be used by release compose");
}
if (!ci.includes("docker build -f deployment/Dockerfile.backend --build-arg MODULE_NAME=alert-service -t fdp37-alert-service")) {
  throw new Error("FDP-37 production-image job must continue using deployment/Dockerfile.backend");
}

for (const token of [
  "LIVE_IN_FLIGHT_REQUEST_KILL",
  "RUNTIME_REACHED_TEST_FIXTURE",
  "checkpoint_reached=true",
  "no_false_success=true",
  "release_image=false",
  "release_candidate_allowed=false",
  "production_deployable=false"
]) {
  if (!readText(summaryMd).includes(token) && !readText(evidenceMd).includes(token)) {
    throw new Error(`FDP-38 proof artifacts missing token: ${token}`);
  }
}
assertIncludes("docs/fdp/fdp_38_merge_gate.md", "fixture image is not a production image");
assertNoRegex([summaryMd, evidenceMd, provenanceJson, "docs/fdp/fdp_38_*.md", "docs/adr/fdp_38_*.md", "docs/testing/fdp_38_*.md"], /release_image: `true`|release_candidate_allowed: `true`|production_deployable: `true`|RUNTIME_REACHED_PRODUCTION_IMAGE: `claimed`|production_enablement: `true`/, "FDP-38 artifacts/docs cannot claim production image live proof or production enablement");

console.log("FDP-38 proof artifacts verified");
