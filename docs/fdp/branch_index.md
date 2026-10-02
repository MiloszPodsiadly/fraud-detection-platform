# Retained FDP Evidence Index

Status: retained chaos and release evidence navigation.

This index contains only proof families whose names are coupled to active CI jobs, artifact protocols, deployment
fixtures, or external release controls. Current runtime architecture belongs in the domain documentation linked from
`docs/index.md`.

## Evidence Map

| FDP | Category | Main claim | Key files/docs | Main CI gate | Non-goals |
| --- | --- | --- | --- | --- | --- |
| FDP-36 | backend / chaos | Real alert-service kill proof is gated and separated from enablement. | `docs/adr/fdp_36_real_chaos_enable_readiness.md`, `docs/fdp/fdp_36_merge_gate.md`, `docs/fdp/fdp_36_enablement_decision_checklist.md`, `docs/testing/fdp_36_real_chaos_proof.md` | `FDP-36 Real Alert-Service Kill Proof` | No automatic bank enablement |
| FDP-37 | backend / chaos | Production-image chaos proof uses packaged evidence. | `docs/adr/fdp_37_production_image_chaos_enable_gate.md`, `docs/fdp/fdp_37_merge_gate.md`, `docs/fdp/fdp_37_enablement_decision_checklist.md`, `docs/testing/fdp_37_production_image_chaos_proof.md` | `FDP-37 Production Image Chaos Proof` | No live production-image instruction-boundary proof |
| FDP-38 | backend / chaos | Live fixture runtime checkpoint proof is explicit. | `docs/fdp/fdp_38_merge_gate.md`, `docs/testing/fdp_38_live_runtime_checkpoint_proof.md`, `docs/adr/fdp_38_live_runtime_checkpoint_fixture_proof.md` | `FDP-38 Live Runtime Checkpoint Chaos Proof` | No production-image live in-flight proof |
| FDP-39 | release / governance | Release artifact separation and approval evidence are named. | `docs/fdp/fdp_39_merge_gate.md`, `docs/adr/fdp_39_release_artifact_separation_governance.md`, `docs/release/fdp_39_*` | `FDP-39 Release Governance Gate` | No production enablement or fixture promotion |
| FDP-40 | release / governance | Platform controls are readiness evidence with external gaps named. | `docs/fdp/fdp_40_merge_gate.md`, `docs/adr/fdp_40_platform_release_controls_signed_provenance_readiness.md`, `docs/release/fdp_40_*` | `FDP-40 Release Controls` | No enforced external platform policy by itself |

## Retention Boundary

These ticket-derived names remain because exact CI identifiers, proof manifests, release scripts, and external control
configuration consume them. Renaming them requires a coordinated external change. They are evidence names, not runtime
architecture names.
