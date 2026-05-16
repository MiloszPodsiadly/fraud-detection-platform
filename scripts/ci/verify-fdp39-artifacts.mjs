import {
  allArtifactText,
  assertChecks,
  assertFile,
  assertIncludes,
  assertJUnitClassPassed,
  assertNoRegex,
  assertNoTokensInText,
  readJson
} from "./artifact-verification-helpers.mjs";

const artifactDir = "alert-service/target/fdp39-governance";
for (const file of [
  "fdp39-release-image-separation.json",
  "fdp39-fixture-dockerfile-usage.md",
  "fdp39-artifact-provenance.json",
  "fdp39-artifact-provenance.md",
  "fdp39-enablement-governance-pack.json",
  "fdp39-enablement-governance-pack.md",
  "fdp39-rollback-governance.json",
  "fdp39-ci-proof-summary.md",
  "fdp39-runtime-immutability.json",
  "fdp39-fixture-dockerfile-usage.json",
  "fdp39-ops-inspection-governance.json"
]) {
  assertFile(`${artifactDir}/${file}`);
}

for (const className of [
  "RegulatedMutationReleaseImageSeparationTest",
  "FixtureDockerfileMustNotBeUsedByReleaseWorkflowTest",
  "Fdp39GovernanceArtifactsTest",
  "Fdp39NoOverclaimDocumentationTest",
  "Fdp39MustNotChangeRuntimeMutationSemanticsTest",
  "RegulatedMutationRecoveryInspectionGovernanceTest"
]) {
  assertJUnitClassPassed({
    className,
    reportPath: `alert-service/target/surefire-reports/TEST-com.frauddetection.alert.regulated.${className}.xml`,
    label: "FDP-39"
  });
}

const separation = readJson(`${artifactDir}/fdp39-release-image-separation.json`);
const provenance = readJson(`${artifactDir}/fdp39-artifact-provenance.json`);
const enablement = readJson(`${artifactDir}/fdp39-enablement-governance-pack.json`);
const rollback = readJson(`${artifactDir}/fdp39-rollback-governance.json`);
const expectedSha = process.env.GITHUB_SHA ?? "";

assertChecks("FDP-39 governance artifacts", {
  separation_release_image_scan_performed: separation.release_image_scan_performed === true,
  separation_release_image_safe: separation.release_image_safe === true,
  separation_forbidden_tokens: separation.forbidden_token_count === 0,
  separation_scanned_files: Number.parseInt(separation.scanned_file_count ?? "0", 10) > 0,
  separation_fixture_code_absent: separation.fixture_code_present === false,
  separation_test_classes_absent: separation.test_classes_present === false,
  separation_profile_absent: separation.fdp38_profile_present === false,
  separation_release_dockerfile: separation.release_dockerfile_path === "deployment/Dockerfile.backend",
  provenance_commit: provenance.commit_sha === expectedSha,
  provenance_release_image: provenance.release_image_name === `fdp39-alert-service:${expectedSha}`,
  provenance_release_id: String(provenance.release_image_id ?? "").startsWith("sha256:"),
  provenance_release_digest: String(provenance.release_image_digest_or_id ?? "").startsWith("sha256:") || String(provenance.release_image_digest_or_id ?? "").includes("@sha256:"),
  provenance_ci_mode: provenance.ci_mode === true,
  provenance_no_local_fallback: provenance.local_fallback_used === false,
  provenance_complete: provenance.immutable_provenance_complete === true,
  provenance_fixture_image: provenance.fixture_image_name === `fdp38-alert-service-test-fixture:${expectedSha}`,
  provenance_fixture_id: String(provenance.fixture_image_id ?? "").startsWith("sha256:"),
  provenance_fixture_not_release: provenance.fixture_image_release_candidate_allowed === false,
  enablement_ready: enablement.ready_for_enablement_review === true,
  enablement_production_disabled: enablement.production_enabled === false,
  enablement_bank_disabled: enablement.bank_enabled === false,
  enablement_human_approval: enablement.human_approval_required === true,
  enablement_dual_control: enablement.dual_control_required === true,
  rollback_plan_present: rollback.rollback_plan_present === true,
  rollback_keeps_fencing: rollback.rollback_does_not_disable_fencing === true,
  rollback_no_enablement_change: rollback.production_enablement_not_changed === true
});

const runtime = readJson(`${artifactDir}/fdp39-runtime-immutability.json`);
const fixtureUsage = readJson(`${artifactDir}/fdp39-fixture-dockerfile-usage.json`);
const ops = readJson(`${artifactDir}/fdp39-ops-inspection-governance.json`);
const expectedEvent = process.env.FDP39_EVENT_NAME ?? "";
const expectedDiffBase = process.env.FDP39_DIFF_BASE_SHA ?? "";
const runtimeMode = runtime.comparison_mode;
const runtimeChangedFileCount = Number.parseInt(runtime.changed_file_count ?? "0", 10);
const additional = {
  runtime_diff_computed: runtime.diff_computed === true,
  runtime_event: runtime.event_name === expectedEvent,
  runtime_comparison_mode: ["pull-request-merge-base-to-head", "push-before-to-head"].includes(runtimeMode),
  runtime_head_sha: runtime.head_sha === expectedSha,
  runtime_base_sha: Boolean(runtime.base_sha),
  runtime_changed_file_count: runtimeChangedFileCount > 0,
  runtime_unchanged: runtime.runtime_semantics_unchanged === true,
  runtime_protected_count: runtime.protected_runtime_file_count === 0,
  fixture_forbidden_count: fixtureUsage.forbidden_occurrence_count === 0,
  fixture_release_safe: fixtureUsage.fixture_dockerfile_release_safe === true,
  ops_admin_only: ops.admin_only_verified === true,
  ops_masking: ops.masking_verified === true,
  ops_audit: ops.audit_on_access_verified === true,
  ops_rate_limit: ops.rate_limit_verified === true,
  ops_audit_failure_policy: ops.audit_failure_policy_verified === true
};
if (expectedEvent === "push") {
  additional.runtime_push_comparison_mode = runtimeMode === "push-before-to-head";
  additional.runtime_push_base_sha = runtime.base_sha === expectedDiffBase;
}
if (expectedEvent === "pull_request") {
  additional.runtime_pr_comparison_mode = runtimeMode === "pull-request-merge-base-to-head";
}
assertChecks("FDP-39 additional governance artifacts", additional);

const generated = allArtifactText(artifactDir, "fdp39-");
assertNoTokensInText("FDP-39 generated artifacts", generated, [
  "LOCAL_",
  "PLACEHOLDER",
  "TO_BE_FILLED",
  "NOT_PROVIDED",
  "UNKNOWN",
  "BANK_CERTIFIED"
]);
if (!generated.includes("`READY_FOR_ENABLEMENT_REVIEW` is not `PRODUCTION_ENABLED`")) {
  throw new Error("FDP-39 enablement artifact must explicitly deny PRODUCTION_ENABLED");
}

assertIncludes("docs/fdp/fdp_39_merge_gate.md", "READY_FOR_ENABLEMENT_REVIEW");
assertIncludes("docs/release/fdp_39_branch_readme.md", "READY_FOR_ENABLEMENT_REVIEW");
assertIncludes("docs/release/fdp_39_final_regulated_mutation_proof_matrix.md", "Fixture proof is not production proof");
assertIncludes(`${artifactDir}/fdp39-enablement-governance-pack.md`, "production_enabled: `false`");
assertIncludes(`${artifactDir}/fdp39-artifact-provenance.md`, "fixture_image_release_candidate_allowed: `false`");
assertNoRegex(["docs/fdp/fdp_39_*.md", "docs/adr/fdp_39_*.md", "docs/release/fdp_39_*.md", `${artifactDir}/fdp39-*.*`], /RUNTIME_REACHED_PRODUCTION_IMAGE.*claimed|production_enablement: `true`|bank_enabled: `true`|production_enabled: `true`/, "FDP-39 docs/artifacts contain a forbidden positive enablement claim");

console.log("FDP-39 governance artifacts verified");
