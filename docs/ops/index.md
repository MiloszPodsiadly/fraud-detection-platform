# Operations Evidence Index

Status: current operations evidence index.

## Scope

This folder contains operational evidence and release-review support material that is not a general operator runbook.
Use [runbooks](../runbooks/index.md) for live operator procedures and [observability](../observability/operations_observability_v2.md)
for current platform observability posture.

## FDP-37 Evidence

| Document | Use when |
| --- | --- |
| [FDP-37 observability thresholds](fdp_37_dashboard_and_alert_thresholds.md) | Reviewing dashboard, metric, alert, and release-gate expectations for production-image chaos proof. |
| [FDP-37 rollback validation output template](fdp_37_rollback_validation_output_template.md) | Reviewing the generated rollback validation artifact contract. |

## Interpretation Rules

- FDP-37 documents in this folder are branch evidence or templates.
- They support review of generated CI artifacts and do not approve production enablement.
- Runtime operator steps belong in [regulated mutation recovery](../runbooks/regulated_mutation_recovery.md).
- Current architecture interpretation starts at [current architecture](../architecture/current_architecture.md).
