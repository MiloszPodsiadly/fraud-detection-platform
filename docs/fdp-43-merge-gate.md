# FDP-43 Merge Gate

Status: branch merge gate.

## Scope

FDP-43 adds shared idempotency primitives and local fraud-case lifecycle retry safety.

## Required Guarantees

- Shared canonical hashing and key validation are reused by local lifecycle idempotency.
- `RegulatedMutationIntentHasher` remains backward compatible and delegates generic hashing to the shared hasher.
- Fraud-case lifecycle POST endpoints require `X-Idempotency-Key`.
- Same key + same payload + same actor/action/scope returns a stable replay response.
- Same key with different payload, actor, action, or scope returns conflict.
- Idempotency record, lifecycle mutation, and audit append commit or roll back together under Mongo transaction mode `REQUIRED`.
- Raw idempotency keys and raw request payloads are not stored or exposed.

## No-Go

- Fraud-case lifecycle idempotency must not use `RegulatedMutationCoordinator`.
- FDP-43 must not introduce lease fencing, FDP-29 evidence-gated finalize, global exactly-once, Kafka/outbox exactly-once, distributed ACID, external finality, WORM storage, legal notarization, or bank certification claims.
- FDP-43 must not create unrelated duplicate SHA-256 request hashing or key validation helpers outside the shared idempotency primitive layer.

## Verification

- `IdempotencyCanonicalHasherTest`
- `SharedIdempotencyKeyPolicyTest`
- `FraudCaseLifecycleIdempotencyConflictPolicyTest`
- `FraudCaseControllerTest`
- `FraudCaseTransactionIntegrationTest`
- `Fdp43FraudCaseLifecycleIdempotencyArchitectureTest`
