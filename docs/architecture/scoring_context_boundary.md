# Scoring Context Boundary

Status: FDP-83 internal foundation only.

## Purpose

`ScoringContext` is the future internal input boundary for fraud signal engines. It belongs in
`fraud-scoring-service`, not `common-events`.

It carries only:

- transaction;
- feature snapshot;
- scoring mode;
- `correlationId`;
- `receivedAt`.

It does not carry:

- metadata in v1;
- raw payloads;
- debug details or exception text;
- temporary flags;
- derived scores or risk levels;
- engine outputs;
- final decisions.

## Boundary

`ScoringContext` is not a Kafka event, not an API DTO, not a storage document, and not a public
cross-service contract. It is not a new source of truth.

`ScoringContext` does not compute features, score, risk level, fallback, or recommendations. This
internal foundation makes no runtime scoring behavior change.
It introduces no final decisioning and no orchestrator.

## Feature Snapshot Semantics

`ScoringContext` defensively copies the top-level `featureSnapshot` map. Null `featureSnapshot` keys
are not allowed, and null `featureSnapshot` values are not allowed. Nested mutable values are not
deep-copied in FDP-83; future engine adapters must treat `featureSnapshot` values as read-only.

A typed or bounded feature snapshot policy belongs to a later branch before real engine adapters
consume it. `ScoringContext` does not compute features. `ScoringContext` does not enrich features.
`ScoringContext` does not normalize features. `ScoringContext` does not validate the business
meaning of features.

## Why The FDP-82 Isolation Guard Was Updated

FDP-82 correctly blocked `ScoringContext` because it was outside FDP-82 scope. FDP-83 intentionally
introduces `ScoringContext` only as an internal `fraud-scoring-service` model. The isolation guard
was narrowed only for internal `ScoringContext`; event extension, API/UI integration, projection
integration, engine-result runtime wiring, and orchestrator integration remain forbidden.

No `common-events` production contract or event schema was changed.

## Integration Preconditions

Before a future branch uses `ScoringContext` in fraud signal engine adapters:

- adapter tests must prove unchanged current scoring behavior;
- tests must prove no raw payload leakage;
- feature snapshot semantics must be explicit;
- mode preservation tests must exist;
- `correlationId` propagation tests must exist;
- `receivedAt` determinism tests must exist.

## Out Of Scope

- `FraudSignalEngine`;
- `FraudEngineDescriptor`;
- `RuleBasedSignalEngine`;
- `PythonMlSignalEngine`;
- `FraudScoringOrchestrator`;
- `FraudIntelligenceResult`;
- `engineResults[]`;
- event extension or alert projection;
- API/UI;
- feedback loop;
- automatic decisioning.
