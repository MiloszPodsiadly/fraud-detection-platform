# Engine Intelligence API Read Model

## Purpose

FDP-96 exposes projected engine intelligence through a bounded read-only API DTO.

## Scope

FDP-96 adds one transaction-scoped read endpoint. FDP-96 does not add list/search endpoints for engine intelligence.
FDP-96 does not add case-level aggregation.

## Endpoint

`GET /api/v1/transactions/scored/{transactionId}/engine-intelligence` returns the bounded read model for an existing
scored transaction.

## Why Transaction-Scoped Endpoint First

FDP-96 uses a dedicated transaction-scoped endpoint first because FDP-95 projection is keyed by transactionId.

## DTO Boundary

FDP-96 does not return EngineIntelligenceProjection directly. Dedicated API DTOs map only persisted FDP-95 fields:
contract version, generation time, comparison status, bounded engine summaries, bounded diagnostic signals, and
bounded warning summaries.

## Authorization Boundary

The endpoint reuses `TRANSACTION_MONITOR_READ`, the authority used by scored transaction reads.
Authorization must be checked before projection lookup. The read service also verifies that the scored transaction exists before it
queries the optional projection.

## Authorization Model

FDP-96 intentionally inherits the current scored-transaction global read authority: `TRANSACTION_MONITOR_READ`.
Under the current model, any user with `TRANSACTION_MONITOR_READ` may read engine intelligence for an existing scored transaction ID.
This is acceptable only because it matches the current scored transaction read boundary.
If the product introduces tenant, case, ownership, or row-level authorization for scored transaction reads, this endpoint must inherit the same resource-level checks before projection lookup.
Projection repository lookup must remain after scored transaction access/existence validation.
FDP-96 does not claim that tenant, case, ownership, or row-level authorization exists today.

## Missing Projection Behavior

Missing projection is normal for old transactions and disabled producer periods. For an existing authorized scored
transaction, the endpoint returns `200` with `available=false` and `reason=NOT_PROJECTED`.

## Projection Read Failures

FDP-96 returns `503` when the projection store cannot be read.
FDP-96 also returns `503` when an existing projection document cannot be safely mapped because it is corrupted, stale, or violates bounded API policy.
The public API intentionally does not expose raw corruption details.
Corrupted projection is not treated as `NOT_PROJECTED` because a projection exists but cannot be safely read.
Future observability should distinguish low-cardinality internal reasons: `PROJECTION_STORE_UNAVAILABLE` and `PROJECTION_INVALID_SHAPE`.
Future metrics and logging must not include transactionId, customerId, accountId, merchantId, raw exception, endpoint, payload, token, or raw corrupted value.

## Read-Model Validation Rationale

API read model does not blindly trust stored Mongo projection.
FDP-96 revalidates stored projection before response mapping.
This is intentional defense-in-depth against stale or corrupted documents.
The validation reuses FDP-92/FDP-95 public/projection contracts to avoid a separate API allowlist source of truth.
Read path remains single-transaction and does not call scoring, ML, rules, orchestrator, Kafka, or producer code.

## Architecture Guard Evidence

Source-scan guards remain architecture tripwires.
FDP-96 relies on behavior-level controller/API serialization tests for API safety.
Source scans are supplementary and can false-positive on comments or renames.
Future FDP-97 UI must add behavior-level UI tests, not only source scans.
Future feedback workflow must add behavior-level workflow tests.

## Operational Status Semantics

TIMEOUT/UNAVAILABLE/DEGRADED must not be represented as LOW risk. A missing operational risk remains null.

## Raw/Internal Leakage Prevention

API must not expose raw/internal fields. Mongo metadata, raw evidence, feature snapshots, payloads, endpoint details,
security material, stack traces, exception messages, internal aggregation objects, and internal projection classes
remain outside the DTO boundary.

## No Decisioning

API must not expose finalDecision/recommendedAction/approve/decline/block. The read model also does not
expose a winning engine, platform risk score, or payment authorization.

## No UI

UI remains FDP-97 scope.

## OpenAPI Boundary

OpenAPI documents only the dedicated transaction-scoped endpoint and bounded DTO schemas. It does not expose the
Mongo projection.

## Future FDP-97 UI

FDP-97 may consume the bounded read-only API after a separate UI review.

## Future Case-Level Aggregation If Needed

Case-level aggregation requires a separate design and authorization review. It is not part of FDP-96.

Read service must not call scoring, ML, rules, orchestrator, Kafka, or producer code.
