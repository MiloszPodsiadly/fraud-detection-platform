# FDP-43 Merge Gate

Status: branch merge gate.

## Scope

FDP-43 adds shared idempotency primitives and local fraud-case lifecycle retry safety.

## Required Guarantees

- Shared canonical hashing and key validation are reused by local lifecycle idempotency.
- `RegulatedMutationIntentHasher` remains backward compatible and delegates generic hashing to the shared hasher.
- All lifecycle POST endpoints require `X-Idempotency-Key` at the HTTP contract level.
- `X-Idempotency-Key` is globally unique within the fraud-case lifecycle idempotency domain; storage enforces one
  record per key hash and stores action, actor, scope, and request hash as conflict-checked claim fields.
- Same key + same payload + same actor/action/scope returns a stable replay response for create, assign, note,
  decision, transition, close, and reopen.
- Same key with different payload, actor, action, or scope returns `409` and does not mutate.
- Concurrent same-key requests do not duplicate lifecycle mutation, audit entry, or idempotency record.
- Depending on timing, the competing concurrent request may receive a stable replay response or
  `code:IDEMPOTENCY_KEY_IN_PROGRESS`; raw Mongo/Spring persistence exceptions must not escape normal same-key
  idempotency races.
- FDP-43 guarantees side-effect idempotency for local lifecycle operations, not deterministic concurrent response
  timing.
- Idempotency record, lifecycle mutation, and audit append commit or roll back together under Mongo transaction mode `REQUIRED`.
- Idempotency completion-save failure rolls back lifecycle mutation and audit append.
- Oversized response snapshots fail closed and roll back lifecycle mutation, audit append, and idempotency record.
- Idempotency records have a positive retention window. Retry after the retention window and eventual Mongo TTL
  deletion may execute as a new lifecycle operation.
- Replay is scoped to a successfully resolved backend actor/principal context; if actor resolution fails before the
  idempotency lookup, FDP-43 does not replay without that valid actor context.
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
- `FraudCaseLifecycleIdempotencyServiceRaceTest`
- `RegulatedMutationIdempotencyPrimitiveCompatibilityTest`

Source-scanning architecture tests are CI guardrails. They prevent accidental public-path usage of non-idempotent
overloads, but they are not formal runtime or security boundaries. The authoritative proof for retry safety is the
real Mongo concurrency and transaction integration suite plus API-level idempotency error tests.
