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

## Missing Projection Behavior

Missing projection is normal for old transactions and disabled producer periods. For an existing authorized scored
transaction, the endpoint returns `200` with `available=false` and `reason=NOT_PROJECTED`.

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

Read service must not call scoring, ML, rules, orchestrator, or Kafka.
