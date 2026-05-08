# FDP-37 Rollback Validation Output Template

This template is copied from `target/fdp37-chaos/fdp37-rollback-validation.md` during release review. It is evidence for review, not production rollback approval.

```text
checkpoint_renewal_can_be_disabled_without_disabling_fencing: true|false
FDP32_fencing_remains_active: true|false
recovery_commands_visible_after_rollback: true|false
API_returns_recovery_or_in_progress_after_rollback: true|false
no_new_success_claims_after_rollback: true|false
final_result: PASS|FAIL
```

Required interpretation:

- `true` means the FDP-37 production-image rollback validation test produced the evidence.
- `false` means the release owner must block enablement review.
- This artifact does not approve a production rollback by itself.
- Any production rollback requires the separate release/config rollback process.
