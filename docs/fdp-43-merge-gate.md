# FDP-43 Merge Gate

Status: branch merge gate.

## Scope

FDP-43 adds shared idempotency primitives and local fraud-case lifecycle retry safety.

## Required Guarantees

- Shared canonical hashing and key validation are reused by local lifecycle idempotency.
- `RegulatedMutationIntentHasher` remains backward compatible and delegates generic hashing to the shared hasher.
- All lifecycle POST endpoints require `X-Idempotency-Key` at the HTTP contract level.
- Same key + same payload + same actor/action/scope returns a stable replay response for create, assign, note,
  decision, transition, close, and reopen.
- Same key with different payload, actor, action, or scope returns `409` and does not mutate.
- Concurrent same-key requests create only one lifecycle mutation, audit entry, and idempotency record.
- Idempotency record, lifecycle mutation, and audit append commit or roll back together under Mongo transaction mode `REQUIRED`.
- Idempotency completion-save failure rolls back lifecycle mutation and audit append.
- Oversized response snapshots fail closed and roll back lifecycle mutation, audit append, and idempotency record.
- Raw idempotency keys and raw request payloads are not stored or exposed.
- Raw idempotency keys, request hashes, payload hashes, stack traces, and exception class names are not exposed in
  HTTP idempotency error responses.
- Shared primitive extraction does not break regulated mutation idempotency semantics.
- FDP-43 CI job `fdp43-fraud-case-idempotency` is green and non-skipped.
- FDP-42 fraud-case suite and regulated-mutation regression suite remain green.

## No-Go

- Fraud-case lifecycle idempotency must not use `RegulatedMutationCoordinator`.
- FDP-43 must not introduce lease fencing, FDP-29 evidence-gated finalize, global exactly-once, Kafka/outbox exactly-once, distributed ACID, external finality, WORM storage, legal notarization, or bank certification claims.
- FDP-43 must not create unrelated duplicate SHA-256 request hashing or key validation helpers outside the shared idempotency primitive layer.
- No merge without concurrency proof.
- No merge without CI FDP-43 job.
- No bounded snapshot claim without runtime enforcement.
- No missing idempotency key may reach the lifecycle mutation path.
- Public fraud-case lifecycle controllers must not call non-idempotent compatibility overloads.

## Verification

- `IdempotencyCanonicalHasherTest`
- `SharedIdempotencyKeyPolicyTest`
- `FraudCaseLifecycleIdempotencyConflictPolicyTest`
- `FraudCaseControllerTest`
- `FraudCaseSecurityIntegrationTest`
- `FraudCaseTransactionIntegrationTest`
- `FraudCaseLifecycleIdempotencyConcurrencyIntegrationTest`
- `FraudCaseLifecycleIdempotencyFailureIntegrationTest`
- `Fdp43FraudCaseLifecycleIdempotencyArchitectureTest`
- `Fdp43FraudCaseLifecyclePublicPathIdempotencyArchitectureTest`
- `RegulatedMutationIdempotencyPrimitiveCompatibilityTest`
