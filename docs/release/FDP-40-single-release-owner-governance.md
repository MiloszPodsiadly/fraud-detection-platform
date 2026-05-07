# FDP-40 Single Release Owner Governance

FDP-40 uses the single release owner model. It does not require dual-control, and it does not claim production approval.

The release owner must be named and accountable for the release evidence packet. The release owner must confirm the immutable
release image digest, required checks, rollback plan, operator drill evidence, and the separate config PR before any future
production enablement request can proceed.

Required governance fields:

- `single_release_owner_model: true`
- `release_owner_required: true`
- `release_owner_must_be_named: true`
- `release_owner_accountability_required: true`
- `release_owner_must_confirm_digest: true`
- `release_owner_must_confirm_required_checks: true`
- `release_owner_must_confirm_rollback_plan: true`
- `release_owner_must_confirm_operator_drill: true`
- `release_owner_must_confirm_config_pr: true`
- `separate_config_pr_required: true`
- `production_enabled: false`
- `dual_control_required: false`

NO-GO:

- unnamed release owner
- mutable tag only
- missing required checks evidence
- missing rollback plan
- missing operator drill evidence
- missing separate config PR
- production enablement claim from FDP-40 readiness evidence
- dual-control approval claim
