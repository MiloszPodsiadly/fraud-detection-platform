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
- PR #50 regression proof: same idempotency key reused across different action/scope/actor/payload returns conflict
  and commits no mutation/audit; only one idempotency record remains.
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
- Backend CI is green.
- FDP-42 CI job `fdp42-fraud-case-management` is green and non-skipped.
- Regulated mutation CI job `regulated-mutation-regression` is green and non-skipped.
- FDP-43 CI job `fdp43-fraud-case-idempotency` is green and non-skipped.
- Required GitHub Actions jobs are `Backend Maven Build`, `FDP-42 Fraud Case Management`,
  `Regulated Mutation Regression Gate`, and `FDP-43 Fraud Case Idempotency`.
- No merge while any required GitHub Actions job is queued, pending, `in_progress`, skipped, missing, cancelled,
  or failed.

## No-Go

- Fraud-case lifecycle idempotency must not use `RegulatedMutationCoordinator`.
- FDP-43 must not introduce lease fencing, FDP-29 evidence-gated finalize, global exactly-once, Kafka/outbox exactly-once, distributed ACID, external finality, WORM storage, legal notarization, or bank certification claims.
- FDP-43 must not create unrelated duplicate SHA-256 request hashing or key validation helpers outside the shared idempotency primitive layer.
- No merge without concurrency proof.
- No merge without fully completed green CI for backend, `fdp42-fraud-case-management`,
  `regulated-mutation-regression`, and `fdp43-fraud-case-idempotency`.
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
- `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest`
- `FraudCaseLifecycleIdempotencyConcurrencyIntegrationTest`
- `FraudCaseLifecycleIdempotencyFailureIntegrationTest`
- `Fdp43FraudCaseLifecycleIdempotencyArchitectureTest`
- `Fdp43FraudCaseLifecyclePublicPathIdempotencyArchitectureTest`
- `FraudCaseLifecycleIdempotencyServiceRaceTest`
- `RegulatedMutationIdempotencyPrimitiveCompatibilityTest`

Source-scanning architecture tests are CI guardrails. They prevent accidental public-path usage of non-idempotent
overloads, but they are not formal runtime or security boundaries. The authoritative proof for retry safety is the
real Mongo concurrency and transaction integration suite plus API-level idempotency error tests.

## PR #50 Regression Proof Matrix

This is the mandatory regression test for the PR #50 scoped-lookup bug.

| Invariant | Test class | Required assertion |
| --- | --- | --- |
| Same key different action conflicts | `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest` | `addNote` then `closeCase` same key => domain conflict, no close mutation, no close audit, one idempotency record |
| Same key different case scope conflicts | `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest` | `addNote` case1 then `addNote` case2 same key => conflict, no case2 note/audit, one record |
| Same key different actor conflicts | `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest` | actor A then actor B same key => conflict, one note/audit, one record |
| Same key different payload conflicts | `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest` | text A then text B same key => conflict, one note/audit, one record |
| Same key same claim replays | `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest` | no duplicate mutation/audit, one record |
