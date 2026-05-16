# FDP-37 Rollback Validation Output Template

Status: branch evidence template.

## Purpose

This template describes the expected shape of `target/fdp37-chaos/fdp37-rollback-validation.md` during FDP-37
release review. Rollback validation is release evidence, not production rollback approval.

## Output Contract

```text
checkpoint_renewal_can_be_disabled_without_disabling_fencing: true|false
FDP32_fencing_remains_active: true|false
recovery_commands_visible_after_rollback: true|false
API_returns_recovery_or_in_progress_after_rollback: true|false
no_new_success_claims_after_rollback: true|false
final_result: PASS|FAIL
```

## Required Interpretation

| Field state | Meaning | Release action |
| --- | --- | --- |
| All checks `true` and `final_result: PASS` | FDP-37 production-image rollback validation test produced the expected evidence. | Continue review with the generated proof artifacts. |
| Any check `false` | Required rollback safety evidence is absent. | Block enablement review. |
| `final_result: FAIL` | The validation did not meet the release-review contract. | Block enablement review and keep generated artifacts for investigation. |

## Non-Claims

This template does not approve a production rollback by itself. Any production rollback requires the separate
release/config rollback process, human approval, environment-specific change control, and platform rollback evidence.
