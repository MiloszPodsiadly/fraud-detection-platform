# ADR: Evidence-Gated Regulated Mutation Finalize

Status: accepted current architecture.

## Decision

`EVIDENCE_GATED_FINALIZE_V1` is the only executable regulated mutation model. `EvidenceGatedFinalizeExecutor` owns
evidence preparation and local finalize; `MongoRegulatedMutationCoordinator` owns command creation, canonical intent,
idempotency conflict detection, claim, and current-model routing.

The local finalize transaction writes the business aggregate, authoritative transactional outbox record, response
snapshot, local success audit evidence, and finalize marker together. Commands move through `REQUESTED`,
`EVIDENCE_PREPARING`, `EVIDENCE_PREPARED`, `FINALIZING`, and `FINALIZED_EVIDENCE_PENDING_EXTERNAL`. Explicit
rejection, validation-failure, recovery, and confirmed-evidence states remain available where their conditions hold.

## Persisted Compatibility Cut

`mutation_model_version` is mandatory. Missing, null, retired, and unknown values are rejected deterministically.
They are not routed to another executor and are not reinterpreted as the current model. A read-only operator preflight
reports unsupported retained records before startup; it never rewrites or purges the database.

## Safety Invariants

- Recovery state wins over a stale response snapshot.
- Required local evidence is prepared before visible business mutation.
- Owner-fenced writes reject stale or expired workers.
- Replay with matching intent does not repeat business mutation, local success audit, or outbox creation.
- An ambiguous durable outcome is not promoted to success without explicit proof.

## Boundary

External anchor readiness and Trust Authority signing readiness are not part of the current local finalize transaction.
Kafka delivery and external evidence confirmation are asynchronous. This design is local evidence-precondition-gated finalize,
not distributed ACID, exactly-once broker delivery, WORM storage, legal notarization, or automatic production approval.
