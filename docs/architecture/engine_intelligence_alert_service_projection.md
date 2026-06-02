# Engine Intelligence Alert-Service Projection

## Purpose

FDP-95 projects bounded engine intelligence internally in alert-service.

## Scope

The `TransactionMonitoringService` keeps the existing base scored-transaction save path and invokes an optional
internal projection after that save succeeds. The projection reads the optional public `engineIntelligence` event
field and writes a bounded Mongo read model.

## Non-goals

FDP-95 does not expose engine intelligence through API/UI.
FDP-95 does not use engine intelligence for decisions.
Final decisioning remains out of scope.

## Projection-only Boundary

The internal projection does not call ML, rules, scoring, alert-management, fraud-case, or payment-authorization
logic. Projection failure must not break base alert projection.

## Storage Model

The `engine_intelligence_projections` Mongo collection stores one replacement document per transaction ID. The
document contains the contract version, generated timestamp, comparison summary, bounded engine results, bounded
diagnostic signals, bounded warnings, counts, and projection timestamps.

## Projection Policy and Limits

The alert-service projection policy reconstructs a safe copy through the shared bounded public event contract
before persistence. It enforces at most 2 engines, 5 diagnostic signals, 10 warnings, 5 reason codes per engine, and
128 characters per bounded string. Only allowlisted engine IDs, statuses, score buckets, warning codes, and public
reason codes are persisted.

## Old Event Compatibility

Old events without engineIntelligence remain compatible. They create no engine-intelligence projection document.

## New Bounded Event Projection

Valid events with the optional public field create an internal read model. Operational engine results and signals
remain nullable for risk level; projection does not invent a fake risk level.

## Invalid/Oversized Safe Omission

Unsupported contract versions and invalid or oversized shapes are omitted with bounded internal reasons. Raw
payloads and exception messages are not logged.

## Idempotency/Replay Safety

Projection must be idempotent under replay. A stable transaction ID replaces the existing Mongo document instead of
appending engines, diagnostic signals, or warnings.

## No Raw/Internal Storage

Only bounded public event contract fields may be stored. Raw evidence, raw contributions, feature vectors,
endpoints, tokens, secrets, stack traces, exception messages, and internal aggregation objects must not be stored.

## No API/UI Exposure

API/UI exposure is future FDP-96/FDP-97 scope. FDP-95 adds no controller, response DTO, OpenAPI field, or
analyst-console component.

## No Decisioning

Projected disagreement, unavailable engines, warnings, and diagnostic risk levels remain internal diagnostics.
They do not change alert severity, priority, recommendation, fraud-case status, assignment, escalation, or payment
authorization.

## Future FDP-96 API Read Model

Any bounded API read model requires separate FDP-96 review. UI rendering remains separate FDP-97 scope.
