# FDP-39 Approval And Rollback Matrix

## Approval Ownership

| Role | Required | Responsibility |
| --- | --- | --- |
| release owner | yes | owns the release/config PR |
| risk owner | yes | accepts residual regulated mutation risk |
| fraud ops owner | yes | owns operator drill readiness |
| security owner | yes | signs endpoint governance and access controls |
| platform owner | yes | owns deployment mechanics |
| rollback owner | yes | owns rollback execution |
| approver 1 | yes | first independent approval |
| approver 2 | yes | second independent approval |

## Rollback Checklist

- disable FDP-34 checkpoint renewal if needed
- keep FDP-32 fencing active
- keep recovery inspection visible
- verify no false success appears
- verify recovery commands remain visible
- verify alert, outbox, and audit counters are stable
- verify FDP-29 flags can be disabled

Rollback validation is governance evidence, not production rollback approval.

## Sample Rollback Output

```json
{
  "rollback_plan_present": true,
  "dual_control_required": true,
  "rollback_does_not_disable_fencing": true,
  "recovery_visibility_required": true,
  "production_enablement_not_changed": true
}
```
