# FDP-29 Failure Windows

This table defines expected future behavior before implementation.

| Failure Window | Possible Persisted State | Visible Business State | API Replay Behavior | Trust-Level Impact | Recovery Behavior | Incident/Degradation |
| --- | --- | --- | --- | --- | --- | --- |
| Crash before command persisted | No command | Unchanged | Same request may create command | None | Normal retry | None |
| Crash after command persisted before evidence preparation | `REQUESTED` | Unchanged | Same key resumes `EVIDENCE_PREPARING` | None unless stale | Coordinator resumes after lease | Stale command if timeout exceeded |
| Crash during `EVIDENCE_PREPARING` | `EVIDENCE_PREPARING`; partial attempted evidence possible | Unchanged | Same key returns/prepares evidence after lease | Degraded if stuck | Retry evidence prep idempotently | Evidence-prep-stalled signal |
| Crash after `EVIDENCE_PREPARED` before `FINALIZING` | `EVIDENCE_PREPARED` | Unchanged | Same key may enter finalize under lease | None | Resume finalize | None |
| Crash during finalize transaction | `FINALIZING` or committed local transaction | Unknown until inspected | Same key returns `FINALIZE_RECOVERY_REQUIRED` if outcome ambiguous | Degraded | Inspect command, aggregate, outbox, snapshot | Recovery-required incident |
| Crash after finalize transaction before response snapshot | Aggregate/outbox/finalize marker may exist; snapshot missing | Committed if transaction committed | Same key must reconstruct only if strategy proves state | Degraded until reconstructed | Reconstruct snapshot or mark recovery required | Missing-snapshot degradation |
| Crash after response snapshot before response returned | Snapshot and visible mutation committed | Committed | Same key returns snapshot | None or pending external | Replay snapshot | None |
| `ATTEMPTED` audit unavailable | Command may be requested/preparing | Unchanged | Same key returns/retries evidence prep or rejected | Degraded in bank mode | Restore audit store and retry | Evidence unavailable incident |
| External anchor unavailable | Evidence prep or external pending state | Unchanged before finalize if required; committed/pending if optional after finalize | Required mode rejects before visible commit; optional mode returns pending external | Degraded if required or prolonged | Retry external publication | External witness unavailable |
| Trust Authority unavailable | Evidence prep blocked when signing required | Unchanged | Same key returns `REJECTED_EVIDENCE_UNAVAILABLE` or preparing | Degraded | Restore signer/authority | Trust authority unavailable |
| Outbox write failure | During finalize transaction | Unchanged if local transaction rolls back | Same key resumes/recovery after proving rollback | Degraded if ambiguous | Retry finalize only if no transaction commit | Outbox write failure signal |
| Business validation failure | `FAILED_BUSINESS_VALIDATION` | Unchanged | Same key returns validation failure | None | No recovery | None |
| Duplicate idempotency retry during each state | Existing command state | Depends on state; no second mutation | Same key/same payload returns persisted status/snapshot | None | No duplicate execution | None |
| Conflicting idempotency retry during each state | Existing command state | Existing state only | Reject conflict | Possible security/audit signal if repeated | No mutation | Conflict metric/log |
| Recovery strategy missing | Requested/preparing or recovery-required | Unchanged unless legacy state already committed | Same key returns rejection/recovery-required | Degraded in bank mode | Deploy/register strategy | Configuration incident |
| Projection update failure | Finalize transaction may still commit source of truth | Source aggregate committed if transaction committed | Return finalized with projection degraded only if source truth is proven | Degraded | Rebuild projection | Projection degradation |
| Broker publish ambiguity after finalize | Outbox `PUBLISH_ATTEMPTED` or `PUBLISH_CONFIRMATION_UNKNOWN` | Committed | Same key returns finalized snapshot with outbox ambiguity | Degraded until resolved | Operator/broker reconciliation | Confirmation-unknown incident |
| External evidence confirmation failure after finalize | `FINALIZED_EVIDENCE_PENDING_EXTERNAL` or degraded status | Committed | Same key returns finalized pending/degraded evidence | Degraded | Retry/reconcile external evidence | External evidence degradation |

## Rules

- Before `FINALIZED_VISIBLE`, visible business state remains unchanged.
- After `FINALIZED_VISIBLE`, failures are recovery/degradation problems, not silent rollback.
- No failure window may report `FINALIZED_EVIDENCE_CONFIRMED` unless evidence confirmation is explicitly proven.
- Idempotency replay must read command truth; it must not rerun finalization from ambiguous states.
