# Regulated Mutation Lease Fencing And Stale Worker Protection

Status: current invariant reference.

The core rule is that claim acquisition is not write fencing. All post-claim transitions are fenced by command id, lease owner, unexpired
lease, execution status, and current model version. There is no silent `repository.save` after claim.

`RegulatedMutationFencedCommandWriter` owns conditional command transitions. `EvidenceGatedFinalizeExecutor` validates
the active lease before business execution and uses the fenced writer for lifecycle transitions. A stale worker must not
write snapshots, outbox ids, local commit markers, audit flags, or terminal status.

`allowedFieldUpdates` is not a general document mutation API.
Identity, lease, ownership, idempotency, request, resource, action, creation, attempt-count, and mutation-model fields are immutable.

Command transition fencing is not business-side-effect rollback by itself. The current runtime requires
transaction-mode `REQUIRED` so command, business, local evidence, outbox, and snapshot writes share the local Mongo
transaction boundary. This does not expand transaction scope beyond Mongo and does not provide a distributed lock or
distributed ACID.

There is no distributed lock.

Missing, null, retired, unknown, or mismatched model versions fail closed. The read-only persisted-model preflight
identifies unsupported retained records; lease fencing never reinterprets them.

Source-string architecture tests are guardrails, not complete architectural proof. Required proof includes conditional
writer unit tests, real Mongo lease takeover tests, stale-worker executor integration, transaction rollback, replay,
recovery, and restart tests.

The lease-owner fenced command transition design is the current runtime contract. It is not production approval, a
distributed lock, distributed ACID, external finality, or exactly-once Kafka delivery.
