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

const artifactDir = "alert-service/target/fdp40-release";
for (const path of [
  `${artifactDir}/fdp40-release-evidence-verification.json`,
  `${artifactDir}/fdp40-release-evidence-verification.md`,
  `${artifactDir}/fdp40-registry-promotion-policy.json`,
  `${artifactDir}/fdp40-runtime-immutability.json`,
  `${artifactDir}/fdp40-proof-pack.json`,
  `${artifactDir}/fdp40-proof-pack.md`,
  "target/fdp40-release/fdp40-release-evidence-verification.json",
  "target/fdp40-release/fdp40-cosign-verification.json"
]) {
  assertFile(path);
}

for (const className of [
  "Fdp40ReleaseManifestValidationTest",
  "Fdp40SignedProvenanceReadinessTest",
  "Fdp40ReleaseEvidenceVerificationTest",
  "Fdp40ReleaseEvidenceVerificationNegativeTest",
  "Fdp40RegistryPromotionPolicyTest",
  "Fdp40RegistryImmutabilityReadinessTest",
  "Fdp40RequiredChecksMatrixTest",
  "Fdp40EnvironmentProtectionGateTest",
  "Fdp40DeploymentEnvironmentReadinessTest",
  "Fdp40BranchProtectionReadinessTest",
  "Fdp40ExternalPlatformControlsMatrixTest",
  "Fdp40EnablementPrTemplateTest",
  "Fdp40SingleReleaseOwnerGovernanceTest",
  "Fdp40UnsupportedClaimsMatrixTest",
  "Fdp40NoOverclaimDocumentationTest",
  "Fdp40MustNotChangeRuntimeMutationSemanticsTest",
  "Fdp40FinalProofPackTest",
  "Fdp40SbomReadinessTest",
  "Fdp40CosignVerificationReadinessTest",
  "Fdp40ResidualPlatformGapsTest",
  "Fdp40ScriptExecutionReadinessTest",
  "Fdp40DocumentationFormattingTest",
  "Fdp40LocalPathHygieneTest"
]) {
  assertJUnitClassPassed({
    className,
    reportPath: `alert-service/target/surefire-reports/TEST-com.frauddetection.alert.regulated.${className}.xml`,
    label: "FDP-40"
  });
}

const verification = readJson(`${artifactDir}/fdp40-release-evidence-verification.json`);
const scriptVerification = readJson("target/fdp40-release/fdp40-release-evidence-verification.json");
const cosign = readJson("target/fdp40-release/fdp40-cosign-verification.json");
const runtime = readJson(`${artifactDir}/fdp40-runtime-immutability.json`);
const proof = readJson(`${artifactDir}/fdp40-proof-pack.json`);
const registry = readJson(`${artifactDir}/fdp40-registry-promotion-policy.json`);
const expectedEvent = process.env.FDP40_EVENT_NAME ?? "";
const expectedDiffBase = process.env.FDP40_DIFF_BASE_SHA ?? "";
const runtimeMode = runtime.comparison_mode;
const runtimeChangedFileCount = Number.parseInt(runtime.changed_file_count ?? "0", 10);

const checks = {
  verification_passed: verification.verification_passed === true,
  script_verification_passed: scriptVerification.verification_passed === true,
  script_readiness_only: scriptVerification.readiness_only === true,
  script_external_controls: scriptVerification.external_platform_controls_required === true,
  script_bank_disabled: scriptVerification.bank_enabled_false === true,
  script_single_owner: scriptVerification.single_release_owner_model === true,
  script_dual_control_false: scriptVerification.dual_control_required === false,
  script_signing_readiness: scriptVerification.signed_provenance_readiness === true,
  script_signing_not_enforced: scriptVerification.signing_enforced_by_fdp40 === false,
  script_registry_not_verified: scriptVerification.registry_immutability_verified_by_fdp40 === false,
  script_required_checks_not_platform_verified: scriptVerification.required_checks_platform_enforcement_verified_by_fdp40 === false,
  cosign_readiness_only: cosign.readiness_only === true,
  cosign_not_performed: cosign.verification_performed === false,
  manifest_valid: verification.manifest_valid === true,
  attestation_valid: verification.attestation_valid === true,
  fdp39_digest_match: verification.fdp39_digest_match === true,
  fixture_not_promoted: verification.fixture_not_promoted === true,
  required_checks_present: verification.required_checks_present === true,
  production_enabled_false: verification.production_enabled_false === true,
  bank_enabled_false: verification.bank_enabled_false === true,
  single_release_owner_model: verification.single_release_owner_model === true,
  dual_control_required_false: verification.dual_control_required === false,
  release_owner_required: verification.release_owner_required === true,
  release_owner_must_be_named: verification.release_owner_must_be_named === true,
  signing_readiness: verification.signed_provenance_readiness === true,
  signing_not_enforced: verification.signing_enforced_by_fdp40 === false,
  registry_not_verified: verification.registry_immutability_verified_by_fdp40 === false,
  required_checks_defined: verification.required_checks_defined === true,
  required_checks_not_platform_verified: verification.required_checks_platform_enforcement_verified_by_fdp40 === false,
  runtime_diff_computed: runtime.diff_computed === true,
  runtime_event: runtime.event_name === expectedEvent,
  runtime_comparison_mode: ["pull-request-merge-base-to-head", "push-before-to-head"].includes(runtimeMode),
  runtime_head_sha: runtime.head_sha === process.env.GITHUB_SHA,
  runtime_base_sha: Boolean(runtime.base_sha),
  runtime_changed_file_count: runtimeChangedFileCount > 0,
  runtime_unchanged: runtime.runtime_semantics_unchanged === true,
  runtime_protected_count: runtime.protected_runtime_file_count === 0,
  registry_policy_valid: registry.promotion_policy_valid === true,
  registry_no_mutable_tag: registry.mutable_tag_only_allowed === false,
  registry_no_fixture: registry.fixture_image_promotion_allowed === false,
  proof_ready: proof.release_controls_ready_for_review === true,
  proof_readiness_only: proof.readiness_only === true,
  proof_external_platform_controls: proof.external_platform_controls_required === true,
  proof_signing_not_performed: proof.signing_verification_performed === false,
  proof_signing_not_enforced: proof.signing_enforced_by_fdp40 === false,
  proof_production_disabled: proof.production_enabled === false,
  proof_bank_disabled: proof.bank_enabled === false,
  proof_single_owner: proof.single_release_owner_model === true,
  proof_dual_control_false: proof.dual_control_required === false,
  proof_no_external_finality: proof.external_finality_claimed === false,
  proof_no_distributed_acid: proof.distributed_acid_claimed === false,
  proof_no_bank_cert: proof.bank_certification_claimed === false
};
if (expectedEvent === "push") {
  checks.runtime_push_comparison_mode = runtimeMode === "push-before-to-head";
  checks.runtime_push_base_sha = runtime.base_sha === expectedDiffBase;
}
if (expectedEvent === "pull_request") {
  checks.runtime_pr_comparison_mode = runtimeMode === "pull-request-merge-base-to-head";
}
assertChecks("FDP-40 artifacts", checks);

assertNoTokensInText("FDP-40 generated artifacts", allArtifactText(artifactDir, "fdp40-"), [
  "LOCAL_",
  "PLACEHOLDER",
  "TO_BE_FILLED",
  "NOT_PROVIDED",
  "UNKNOWN",
  "BANK_CERTIFIED"
]);

assertIncludes("docs/release/fdp_40_branch_readme.md", "READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED");
assertIncludes(".github/PULL_REQUEST_TEMPLATE/fdp_enablement_config_change.md", "READY_FOR_ENABLEMENT_REVIEW is not PRODUCTION_ENABLED");
assertIncludes("docs/release/fdp_40_single_release_owner_governance.md", "single release owner model");
assertIncludes(".github/PULL_REQUEST_TEMPLATE/fdp_enablement_config_change.md", "single release owner model");
assertIncludes("docs/release/fdp_40_unsupported_claims_matrix.md", "signed image does not mean external finality");
assertNoRegex(["docs/fdp/fdp_40_*.md", "docs/adr/fdp_40_*.md", "docs/release/fdp_40_*.md", `${artifactDir}/fdp40-*.*`], /production_enabled: `true`|bank_certification_claimed: `true`|external_finality_claimed: `true`|distributed_acid_claimed: `true`|dual_control_required: `true`/, "FDP-40 docs/artifacts contain a forbidden positive enablement or finality claim");

console.log("FDP-40 release controls artifacts verified");
