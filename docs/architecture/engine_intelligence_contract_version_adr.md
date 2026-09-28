# Engine Intelligence Contract Version Decision

Status: current FDP-129 decision.

FDP-129 keeps `EngineIntelligenceSummary.contractVersion=1` while adding optional `velocity.primary` and explicit
`RULES_VS_ML` comparison identity because the repository-controlled migration is additive for current v1 consumers:
common-events, fraud-scoring emission, alert-service projection/read/API DTOs, OpenAPI, and Analyst Console validators
share the same bounded public contract and are migrated atomically inside the repository deployment boundary.

Version 1 requires Rules and ML for comparison semantics. `velocity.primary` is an optional third diagnostic engine.
When Velocity is present, the canonical engine order is `rules.primary`, `ml.python.primary`, `velocity.primary`.
Duplicate `engineId`, wrong engineId/type pairs, unknown engine IDs, four-engine payloads, and out-of-order engine
arrays are invalid. A future fourth engine or changed comparison meaning requires a versioned contract review.

The v1 comparison object is not all-engine agreement. It is explicitly `comparisonType=RULES_VS_ML` with
`comparedEngineIds=["rules.primary","ml.python.primary"]`. Velocity remains a separate diagnostic result and signal;
it must not participate in Rules-vs-ML score delta semantics. New v1 producers must emit explicit comparison identity.

Every comparison object must carry explicit `comparisonType=RULES_VS_ML` and
`comparedEngineIds=["rules.primary","ml.python.primary"]`. The read boundary does not infer either field from the
outer event `modelVersion`. Missing or partial identity, reversed IDs, Velocity-containing comparison IDs, unknown
IDs, and inconsistent comparison semantics are rejected rather than completed.

The repository-controlled hard cut deliberately retires historical comparison normalization. This does not prove the
absence of external consumers; it records the accepted compatibility break for retained payloads that do not satisfy
the current contract. Consumers must not hide Velocity, map it to Rules or ML, or fabricate a current shape from a
malformed historical payload.
