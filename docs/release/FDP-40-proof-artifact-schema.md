# FDP-40 Proof Artifact Schema

## `fdp40-release-evidence-verification.json`

- `verification_passed`
- `manifest_valid`
- `attestation_valid`
- `fdp39_digest_match`
- `fixture_not_promoted`
- `required_checks_present`
- `production_enabled_false`
- `no_mutable_tag_only`
- `failure_reasons`

## `fdp40-registry-promotion-policy.json`

- `promotion_policy_valid`
- `release_digest_bound`
- `mutable_tag_only_allowed`
- `fixture_image_promotion_allowed`
- `registry_immutability_verified`
- `production_readiness`

## `fdp40-runtime-immutability.json`

- `diff_computed`
- `base_ref`
- `merge_base`
- `changed_file_count`
- `protected_runtime_file_count`
- `runtime_semantics_unchanged`

## `fdp40-proof-pack.json`

- `release_controls_ready_for_review`
- `production_enabled` is not true; must be false
- `release_digest_bound`
- `signature_subject_required`
- `attestation_required`
- `registry_immutability_required`
- `mutable_tag_only_allowed`
- `fixture_image_promotion_allowed`
- `dual_control_required`
- `environment_protection_required`
- `separate_config_pr_required`
- `runtime_semantics_changed`
- `external_finality_claimed`
- `distributed_acid_claimed`
- `bank_certification_claimed`
- `residual_platform_gaps`
