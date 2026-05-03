# FDP-29 API Response Contract

This is the response contract for the FDP-29 feature-flagged submit-decision evidence-gated finalize prototype. It is active only when both evidence-gated finalize flags are enabled for submit-decision.

Core rule:

> No endpoint returns an updated business resource when state is not `FINALIZED_VISIBLE` or stronger.

## Common Body Shape

```json
{
  "operation_status": "EVIDENCE_PREPARING",
  "command_id": "regulated-command-id",
  "idempotency_key_hash": "bounded-hash",
  "resource_type": "ALERT",
  "resource_id": "alert-id",
  "current_snapshot": {},
  "updated_resource": null,
  "retry_after_seconds": 5,
  "reason_code": "EVIDENCE_PREPARING",
  "operator_action": null
}
```

`current_snapshot` is the pre-finalize/current committed state. `updated_resource` is present only for `FINALIZED_VISIBLE`, `FINALIZED_EVIDENCE_PENDING_EXTERNAL`, or `FINALIZED_EVIDENCE_CONFIRMED`.

## Status Contract

| Status | HTTP | Business Fields Included? | Updated Resource Included? | Current Snapshot Included? | Retry Guidance | Idempotency Behavior | Operator Action |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `PENDING_EVIDENCE` | 202 | No committed updates | No | Yes when available | Retry same key | Same key returns command state | None |
| `EVIDENCE_PREPARING` | 202 | No committed updates | No | Yes | Retry after short delay | Same key returns in-progress/preparing | None unless stuck |
| `EVIDENCE_PREPARED` | 202 | No committed updates | No | Yes | Retry may allow coordinator to finalize | Same key may continue only under safe lease | None |
| `FINALIZING` | 202 | No committed updates | No | Yes | Retry same key; do not submit new key | Same key must not rerun finalize blindly | Investigate if lease expires |
| `FINALIZED_VISIBLE` | 200 | Yes | Yes | Optional | Compatibility/repair state only | Same key returns response snapshot when persisted by older/interrupted flow | None |
| `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | 200 | Yes | Yes | Optional | Poll/reconcile evidence if needed | Same key returns response snapshot with pending evidence | Operator may monitor external evidence |
| `FINALIZED_EVIDENCE_CONFIRMED` | 200 | Yes | Yes | Optional | No retry required | Same key returns final snapshot | None |
| `REJECTED_EVIDENCE_UNAVAILABLE` | 503 or 202 by endpoint policy | No committed updates | No | Yes when available | Retry after dependency recovery or use new command if policy allows | Same key returns rejection | Restore evidence dependency |
| `FAILED_BUSINESS_VALIDATION` | 400 or 422 | No committed updates | No | Yes when available | Do not retry unchanged payload | Same key returns validation failure | Correct request/business state |
| `FINALIZE_RECOVERY_REQUIRED` | 202 | No new success claim | No unless recovered snapshot already proves commit | Yes when available | Do not retry as new command | Same key returns recovery-required | Run authorized recovery |
| `COMMIT_UNKNOWN` | 202 | No new success claim | No | Yes when available | Inspect/recover, then retry same key | Same key returns unknown until recovered | Run recovery/inspection |

## Response Rules

- Pending and recovery responses must not include updated business state as if committed.
- Rejected responses must not mutate visible business state.
- Finalized responses must include a stable response snapshot for replay.
- Same idempotency key plus same payload returns the persisted state or snapshot.
- Same idempotency key plus different payload or different actor is rejected as conflict.
- External evidence pending is not an error if local finalize succeeded and policy allows asynchronous confirmation.
- External evidence confirmed must be backed by explicit evidence status, not inferred from absence of errors.
- New FDP-29 submit-decision commands durably persist `FINALIZED_EVIDENCE_PENDING_EXTERNAL` as the local-visible state inside the local Mongo transaction. `FINALIZED_VISIBLE` is retained only for compatibility and repair.
