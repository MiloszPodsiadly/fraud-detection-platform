# Fraud Case Management Architecture

Status: current architecture note.

## Boundary

Fraud Case Management is implemented inside `alert-service`. It reuses existing service-local DTOs, Mongo
documents, repositories, exception handling, authentication, and RBAC. It does not introduce a new service,
message topic, outbox contract, ML path, or release-governance model.

FDP-43 adds local lifecycle idempotency for fraud-case lifecycle POST endpoints. It reuses shared idempotency
primitives and conflict semantics from the existing regulated mutation architecture, but the lifecycle endpoints are
not implemented through `RegulatedMutationCoordinator`, are not FDP-29 evidence-gated finalize, are not lease fenced,
are not global exactly-once, and are not external finality. The older regulated `PATCH` path remains isolated as
compatibility behavior.

## Flow

```text
FraudCaseController
  -> FraudCaseManagementService
  -> FraudCaseLifecycleService / FraudCaseQueryService
  -> FraudCaseLifecycleIdempotencyService
  -> FraudCaseLifecycleIdempotencyRepository
  -> FraudCaseTransitionPolicy
  -> FraudCaseRepository / FraudCaseNoteRepository / FraudCaseDecisionRepository
  -> FraudCaseSearchRepository / MongoFraudCaseSearchRepository
  -> FraudCaseAuditService
  -> FraudCaseAuditRepository
```

Controllers handle HTTP and validation only. `FraudCaseManagementService` is the compatibility facade. It delegates
analyst lifecycle work to `FraudCaseLifecycleService`, read/search/audit work to `FraudCaseQueryService`, and keeps
system event ingestion plus the legacy regulated `PATCH` path separate. `FraudCaseTransitionPolicy` owns lifecycle validation.
`FraudCaseLifecycleIdempotencyService` wraps local lifecycle POST mutations and stores bounded replay snapshots.
`FraudCaseSearchRepository` owns repository-level Mongo search instead of in-memory `findAll().stream` filtering.
`FraudCaseAuditService` appends immutable audit entries. `FraudCaseResponseMapper` separates persistence documents
from API responses.

## State And Append-Only Records

- `FraudCaseDocument` stores case state, stable `caseNumber`, linked alerts, assignment, priority, risk level,
  timestamps, closure metadata, and optimistic version.
- `FraudCaseNoteDocument` is append-only.
- `FraudCaseDecisionDocument` is append-only.
- `FraudCaseAuditEntryDocument` is append-only and ordered by `occurredAt`.
- `FraudCaseLifecycleIdempotencyRecordDocument` stores hashed idempotency keys, request hashes, action/actor/scope,
  bounded response snapshots, local status, and a TTL retention timestamp. It does not store raw idempotency keys or
  raw request payloads.

## Transaction Direction

All idempotent analyst lifecycle mutations are wrapped by `FraudCaseLifecycleIdempotencyService`, which uses
`RegulatedMutationTransactionRunner.runLocalCommit` as the local Mongo transaction boundary. In transaction-required
deployments, Mongo transaction manager support provides atomic idempotency record + case state + audit persistence.

FDP-42 analyst lifecycle atomicity requires Mongo transactions. Bank-grade case+audit atomic rollback requires
`app.regulated-mutations.transaction-mode=REQUIRED`. If transaction mode is `OFF`, FDP-42 must not claim rollback
atomicity. Integration tests prove atomicity using `MongoTransactionManager` and transaction-mode `REQUIRED`.

This transaction runner is used only as the local Mongo transaction boundary for FDP-42 lifecycle operations. It does
not make those operations regulated commands, lease-fenced commands, or externally finalized commands.

## Local Idempotency Semantics

- `X-Idempotency-Key` is required for lifecycle POST endpoints.
- `X-Idempotency-Key` is globally unique within the fraud-case lifecycle idempotency domain. The repository lookup is
  by key hash only; action, backend actor, scope, and request hash are stored as claim fields and validated for replay.
- Same key, payload, resolved backend actor, action, and scope replays the stored response snapshot.
- Same key with different payload, actor, action, or scope is a local idempotency conflict.
- Replay does not re-run the lifecycle mutation and does not append another audit entry.
- Concurrent same-key requests do not duplicate lifecycle mutation, audit entry, or idempotency record.
  Depending on timing, the competing request may receive a stable replay response or an in-progress local conflict.
- FDP-43 guarantees side-effect idempotency for local lifecycle operations, not deterministic concurrent response
  timing.
- In-progress duplicate operations return a local conflict response and can be retried later with the same key.
- Raw idempotency keys and raw request payloads are not stored or exposed.
- Records are retained for `app.fraud-cases.idempotency.retention` (`PT24H` by default). After the retention window
  and eventual Mongo TTL deletion, retrying the same key may execute as a new lifecycle operation.
- Replay requires a valid authenticated/resolved actor context; failed actor resolution fails before replay and does
  not mutate.
- Response snapshots are runtime-bounded. Oversized snapshots fail closed inside the local transaction and roll back
  the idempotency record, lifecycle mutation, and audit append.
- Public HTTP lifecycle POSTs must call only service overloads that accept the resolved `X-Idempotency-Key`; overloads
  without a key exist only for internal/backward-compatibility callers and must not become controller mutation paths.

Source-scanning architecture tests are guardrails for accidental public-path misuse of no-key overloads. They are not
formal runtime or security boundaries. Runtime correctness is proven by Mongo transaction, concurrency, rollback, and
API-level idempotency tests.

## Read And Ingestion Boundaries

GET/list/search endpoints are pure reads and do not refresh or persist missing transaction details. System-generated
case candidate ingestion happens through `handleScoredTransaction(...)`, not through read endpoints. That ingestion
path is event-derived enrichment and is not an analyst lifecycle mutation, investigator decision, or FDP-42 analyst
audit claim.

## Invariants

- Case creation requires at least one alert id.
- Status transitions go through `FraudCaseTransitionPolicy`.
- Closed cases cannot be modified except explicit reopen.
- `RESOLVED` cases remain mutable until they are explicitly closed.
- Notes, decisions, and audit entries have no update/delete endpoint.
- Manual create links alerts through `linkedAlertIds`; it does not populate `transactionIds` with alert ids.
- Audit details are sanitized before append and exclude raw request payloads, idempotency keys, lease owners, payload
  hashes, exceptions, and stack traces.
- Append-only is enforced by application API shape and architecture tests; it is not WORM storage or legal
  notarization.
- Existing regulated mutation semantics for legacy fraud-case `PATCH` remain unchanged.
- FDP-43 shared idempotency primitives must not become a second regulated mutation framework.
