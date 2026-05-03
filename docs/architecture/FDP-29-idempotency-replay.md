# FDP-29 Idempotency Replay Semantics

Invariant:

> No replay may rerun business finalization unless the state machine explicitly proves finalization never happened.

## Common Conflict Rules

- Same key + same payload: return or advance the persisted command according to safe state rules.
- Same key + different payload: reject as idempotency conflict.
- Same key + different actor: reject as idempotency conflict.
- Active lease: return in-progress status; do not steal work.
- Expired lease: a coordinator may resume only from states where recovery rules prove safe resumption.
- Missing snapshot: never fabricate committed success; reconstruct only through registered recovery strategy.

## Per-State Semantics

| State | Same Key + Same Payload | Same Key + Different Payload | Same Key + Different Actor | Expired Lease | Active Lease | Missing Snapshot | Safe Replay Conditions | Forbidden Rerun Conditions |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `REQUESTED` | Create/read command and move to evidence prep | Reject conflict | Reject conflict | Claim and prepare evidence | Return pending | Not applicable | No evidence/finalize started | Do not mutate business state |
| `EVIDENCE_PREPARING` | Return preparing or retry idempotent evidence prep | Reject conflict | Reject conflict | Retry evidence prep if no finalize marker | Return preparing | Not applicable | Deterministic audit keys allow retry | Do not finalize before `EVIDENCE_PREPARED` |
| `EVIDENCE_PREPARED` | Return prepared or safely claim finalize | Reject conflict | Reject conflict | Claim finalize if no active owner | Return prepared/finalizing | Not applicable | Required preconditions still valid | Do not bypass transaction capability/outbox checks |
| `FINALIZING` | Return finalizing or recovery-required | Reject conflict | Reject conflict | Inspect persisted state before action | Return finalizing | Recovery required unless strategy reconstructs | Only resume if no aggregate mutation, outbox, or finalize marker exists | Do not rerun finalize when transaction outcome is unknown |
| `FINALIZED_VISIBLE` | Return response snapshot | Reject conflict | Reject conflict | Not relevant | Not relevant | Reconstruct from aggregate only if strategy proves exact intent | Snapshot or proven aggregate/outbox/finalize marker | Do not write business state again |
| `FINALIZED_EVIDENCE_PENDING_EXTERNAL` | Return committed snapshot with pending external evidence | Reject conflict | Reject conflict | Reconcile external evidence only | Reconciliation in progress | Reconstruct committed snapshot if safe | External publication retry/reconciliation | Do not rerun business mutation |
| `FINALIZED_EVIDENCE_CONFIRMED` | Return final committed/confirmed snapshot | Reject conflict | Reject conflict | Not relevant | Not relevant | Reconstruct from durable evidence if possible | Evidence still valid | Do not create new evidence with same command unless idempotent |
| `REJECTED_EVIDENCE_UNAVAILABLE` | Return rejection; optional explicit retry policy may restart evidence prep without mutation | Reject conflict | Reject conflict | Retry only if policy allows and no visible mutation exists | Return rejection/preparing | Not applicable | No business mutation exists | Do not transition directly to finalized |
| `FAILED_BUSINESS_VALIDATION` | Return validation failure | Reject conflict | Reject conflict | Not relevant | Not relevant | Not applicable | New command key after corrected request/business state | Do not retry unchanged payload as success |
| `FINALIZE_RECOVERY_REQUIRED` | Return recovery-required until resolved | Reject conflict | Reject conflict | Authorized recovery may inspect | Return recovery-required | Recovery strategy required | Recovery proves committed or not committed state | Do not rerun finalize blindly |

## Lease Rules

- Lease ownership is advisory for work coordination, not proof of commit.
- Expired lease permits inspection and safe resumption only from non-finalized states.
- Active lease duplicate requests must not execute duplicate business logic.

## Snapshot Rules

- A finalized command should have a response snapshot.
- Missing snapshot after possible finalize is a recovery condition.
- Reconstructed snapshots must be based on canonical intent hash, actor, resource, aggregate state, outbox record, and evidence markers.
