# FDP-44 Merge Gate

Status: branch merge gate.

## Scope

FDP-44 is final polish for local fraud-case lifecycle idempotency. It removes public non-idempotent lifecycle mutation
overloads, keeps fraud-case lifecycle idempotency fail-closed, adds explicit replay snapshot DTO proof, and adds
operator docs.

## Required Guarantees

- Public fraud-case lifecycle mutation services expose only idempotency-key overloads.
- Public HTTP lifecycle mutation paths require `X-Idempotency-Key`.
- `FraudCaseLifecycleIdempotencyService` is required by construction for lifecycle mutations.
- Fraud-case lifecycle idempotency is not routed through `RegulatedMutationCoordinator`.
- Unknown `DataAccessException` and unknown `TransactionSystemException` are not converted into replay,
  `IDEMPOTENCY_KEY_IN_PROGRESS`, or domain conflict.
- Known same-key duplicate/write-conflict Mongo races keep the controlled FDP-43 outcomes: replay-equivalent success,
  conflict for different claim, or in-progress when no completed record is visible.
- Same-key same-claim retry does not create duplicate mutation/audit/idempotency record inside the active local
  retention window.
- Identical concurrent response timing is not guaranteed.
- New FDP-44 idempotency snapshot writes must serialize only explicit `FraudCaseLifecycleReplaySnapshot` envelopes.
- FDP-44 stores the public lifecycle response DTO inside a versioned replay snapshot envelope.
- The replay snapshot is safe because it stores only public response DTOs already returned by the endpoint, not
  persistence documents, raw requests, raw keys, hashes, stack traces, or internal exception details.
- Replay equivalence is proven by JSON equality between first execution and replay.
- Snapshot write path must fail closed if the lifecycle response type is unsupported.
- Raw response fallback is allowed only on restore/read path for backward compatibility with pre-FDP-44 snapshots.
- Raw response fallback must never be used for new snapshot writes.
- First execution response JSON must equal replay response JSON for every fraud-case lifecycle mutation.
- Case lifecycle replay uses the explicit `FraudCaseResponse` public response contract, not a partial reconstructed
  `FraudCaseDocument`.
- Explicit replay snapshots must fail closed for malformed payloads, unsupported versions, unsupported types, and
  response type mismatches.
- Legacy raw restore fallback is allowed only for positively identified pre-FDP-44 raw response shapes without snapshot
  markers.
- Replay snapshots must not include raw idempotency keys, idempotency key hashes, request hashes, lease owners, stack
  traces, or raw exception text.
- Idempotency records use positive retention and `expiresAt` TTL. After retention and eventual Mongo TTL cleanup, retry
  with the same key may execute as a new local lifecycle operation.
- Metrics remain low-cardinality and must not label by actor id, case id, idempotency key, key hash, request hash, raw
  exception, stack trace, path, or lease owner.

## No-Go

- Do not add a new idempotency framework.
- Do not add retries, schedulers, or recovery workers.
- Do not change `RegulatedMutationCoordinator`.
- Do not change transaction boundaries.
- Do not claim global exactly-once, distributed ACID, Kafka/outbox exactly-once, external finality, FDP-29 finality,
  lease fencing, WORM storage, legal notarization, bank certification, or deterministic concurrent response ordering.
- Do not manually edit fraud-case lifecycle idempotency records.
- NO-GO if snapshot write path uses raw response fallback.
- NO-GO if unsupported lifecycle response type is silently serialized.
- NO-GO if a new lifecycle response type is added without replay snapshot mapper coverage.
- NO-GO if replay response drops public response fields.
- NO-GO if CASE replay reconstructs a partial persistence `FraudCaseDocument`.
- NO-GO if selected-field equality is the only replay proof.

## Required CI

- Backend Maven Build
- FDP-42 Fraud Case Management
- Regulated Mutation Regression Gate
- FDP-43 Fraud Case Idempotency
- FDP-44 Fraud Case Idempotency Hardening

FDP-44 is NO-GO while any required workflow is queued, pending, in_progress, skipped, missing, cancelled, or failed.
Code review near-GO does not override incomplete CI. A regulated-bank merge gate requires complete green CI on the
current head SHA.

## Verification

- `Fdp44FraudCaseLifecycleApiSurfaceStructuralTest`
- `Fdp44FraudCaseLifecycleReplaySnapshotTest`
- `Fdp44FraudCaseLifecycleReplaySnapshotFailClosedTest`
- `Fdp44FraudCaseLifecycleReplaySnapshotCoverageTest`
- `Fdp44FraudCaseLifecycleReplaySnapshotMapperStructuralTest`
- `Fdp44FraudCaseLifecycleReplayEquivalenceIntegrationTest`
- `Fdp44FraudCaseIdempotencyRetentionOperationalTest`
- `Fdp44FraudCaseIdempotencyOperationalDocsNoOverclaimTest`
- `FraudCaseLifecycleIdempotencyServiceRaceTest`
- `FraudCaseLifecycleIdempotencyGlobalKeyRegressionIntegrationTest`
- `FraudCaseLifecycleIdempotencyConcurrencyIntegrationTest`
- `FraudCaseLifecycleIdempotencyFailureIntegrationTest`
- `AlertServiceMetricsTest`
