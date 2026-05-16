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

const summaryMd = "alert-service/target/fdp37-chaos/fdp37-proof-summary.md";
const summaryJson = "alert-service/target/fdp37-chaos/fdp37-proof-summary.json";
const enablementMd = "alert-service/target/fdp37-chaos/fdp37-enablement-review-pack.md";
const enablementJson = "alert-service/target/fdp37-chaos/fdp37-enablement-review-pack.json";
const evidence = "alert-service/target/fdp37-chaos/evidence-summary.md";
const rollbackMd = "alert-service/target/fdp37-chaos/fdp37-rollback-validation.md";
const rollbackJson = "alert-service/target/fdp37-chaos/fdp37-rollback-validation.json";

for (const path of [summaryMd, summaryJson, enablementMd, enablementJson, evidence, rollbackMd, rollbackJson]) {
  assertFile(path);
}

for (const className of [
  "RegulatedMutationProductionImageChaosIT",
  "RegulatedMutationProductionImageEvidenceIntegrityIT",
  "RegulatedMutationProductionImageConfigParityIT",
  "RegulatedMutationProductionImageRollbackIT",
  "RegulatedMutationProductionImageRequiredTransactionChaosIT"
]) {
  assertJUnitClassPassed({
    className,
    reportPath: `alert-service/target/surefire-reports/TEST-com.frauddetection.alert.regulated.${className}.xml`,
    label: "FDP-37"
  });
}

const expectedSha = process.env.GITHUB_SHA ?? "";
const summary = readJson(summaryJson);
assertChecks("FDP-37 proof summary", {
  commit_sha: summary.commit_sha === expectedSha,
  image_name: String(summary.image_name ?? "").includes(`fdp37-alert-service:${expectedSha}`),
  image_id: String(summary.image_id ?? "").startsWith("sha256:"),
  image_digest_or_id: ![undefined, null, "", "LOCAL_IMAGE_DIGEST_NOT_PROVIDED"].includes(summary.image_digest_or_id),
  dockerfile_path: summary.dockerfile_path === "deployment/Dockerfile.backend",
  network_mode: summary.network_mode === "testcontainers-shared-network",
  host_networking_used: summary.host_networking_used === false,
  killed_container_id_masked: ![undefined, null, "", "absent"].includes(summary.killed_container_id_masked),
  restarted_container_id_masked: ![undefined, null, "", "absent"].includes(summary.restarted_container_id_masked),
  scenario_count: Number.parseInt(summary.scenario_count ?? "0", 10) >= 5,
  final_result: summary.final_result === "PASS",
  live_in_flight_proof_executed: summary.live_in_flight_proof_executed === false
});

const rollback = readJson(rollbackJson);
assertChecks("FDP-37 rollback validation", {
  checkpoint_renewal_can_be_disabled_without_disabling_fencing: rollback.checkpoint_renewal_can_be_disabled_without_disabling_fencing === true,
  FDP32_fencing_remains_active: rollback.FDP32_fencing_remains_active === true,
  recovery_commands_visible_after_rollback: rollback.recovery_commands_visible_after_rollback === true,
  API_returns_recovery_or_in_progress_after_rollback: rollback.API_returns_recovery_or_in_progress_after_rollback === true,
  no_new_success_claims_after_rollback: rollback.no_new_success_claims_after_rollback === true
});

const enablement = readJson(enablementJson);
assertChecks("FDP-37 enablement review pack", {
  commit_sha: enablement.commit_sha === expectedSha,
  image_id: String(enablement.image_id ?? "").startsWith("sha256:"),
  image_digest_or_id: ![undefined, null, "", "LOCAL_IMAGE_DIGEST_NOT_PROVIDED"].includes(enablement.image_digest_or_id),
  dockerfile_path: enablement.dockerfile_path === "deployment/Dockerfile.backend",
  fdp37_job_status: enablement.fdp37_job_status === "PASS",
  regulated_mutation_regression_status: enablement.regulated_mutation_regression_status === "PASS",
  fdp35_status: enablement.fdp35_status === "PASS",
  fdp36_status: enablement.fdp36_status === "PASS",
  required_transaction_scenario_executed: enablement.required_transaction_scenario_executed === true,
  production_enablement: enablement.production_enablement === false,
  release_config_pr_required: enablement.release_config_pr_required === true,
  human_approval_required: enablement.human_approval_required === true,
  operator_drill_required_before_enablement: enablement.operator_drill_required_before_enablement === true
});

assertIncludes(summaryMd, `fdp37-alert-service:${expectedSha}`);
assertIncludes(summaryMd, expectedSha);
assertIncludes(evidence, "PRODUCTION_IMAGE_CONTAINER_KILL");
assertIncludes(evidence, "PRODUCTION_IMAGE_RESTART_API_PROOF");
assertIncludes(evidence, "transaction_mode=REQUIRED");
assertIncludes(evidence, "network_mode=testcontainers-shared-network");
assertIncludes(evidence, "host_networking_used=false");
assertIncludes(summaryMd, "READY_FOR_ENABLEMENT_REVIEW is not production enablement");
assertIncludes(enablementMd, "READY_FOR_ENABLEMENT_REVIEW");
assertIncludes(summaryMd, "live_in_flight_proof_executed: `false`");
assertIncludes(rollbackMd, "no_new_success_claims_after_rollback: `true`");

assertNoRegex([summaryMd, evidence, enablementMd], /network_mode=host|network_mode: host|Linux CI host networking/, "FDP-37 proof artifacts must not use host networking");
assertNoTokensInText("FDP-37 proof artifacts", [readText(summaryMd), readText(summaryJson), readText(enablementMd), readText(enablementJson)].join("\n"), [
  "TO_BE_FILLED_BY_CI",
  "PLACEHOLDER",
  "LOCAL_IMAGE_ID_NOT_PROVIDED",
  "LOCAL_IMAGE_DIGEST_NOT_PROVIDED"
]);
assertNoRegex(["docs/fdp/fdp_37_*.md", "docs/adr/fdp_37_*.md", "docs/testing/fdp_37_*.md", "docs/ops/fdp_37_*.md"], /LIVE_IN_FLIGHT_REQUEST_KILL/, "FDP-37 docs cannot claim live in-flight proof unless the required artifact contains LIVE_IN_FLIGHT_REQUEST_KILL");
assertNoRegex([summaryMd, evidence], /alpine|busybox|dummy/i, "FDP-37 production-image chaos cannot use dummy/alpine/busybox images");

console.log("FDP-37 proof artifacts verified");
