# Engine Intelligence Contract Version Decision

Status: current FDP-129 decision.

FDP-129 keeps `EngineIntelligenceSummary.contractVersion=1` while adding optional `velocity.primary` and explicit
`RULES_VS_ML` comparison identity because the repository-controlled migration is additive for current v1 consumers:
common-events, fraud-scoring emission, alert-service projection/read/API DTOs, OpenAPI, and Analyst Console validators
share the same bounded public contract.

Version 1 requires Rules and ML for comparison semantics. `velocity.primary` is an optional third diagnostic engine.
When Velocity is present, the canonical engine order is `rules.primary`, `ml.python.primary`, `velocity.primary`.
Duplicate `engineId`, wrong engineId/type pairs, unknown engine IDs, four-engine payloads, and out-of-order engine
arrays are invalid. A future fourth engine or changed comparison meaning requires a versioned contract review.

The v1 comparison object is not all-engine agreement. It is explicitly `comparisonType=RULES_VS_ML` with
`comparedEngineIds=["rules.primary","ml.python.primary"]`. Velocity remains a separate diagnostic result and signal;
it must not participate in Rules-vs-ML score delta semantics. New v1 producers must emit explicit comparison identity.

Legacy v1 comparison objects produced before FDP-129 that contain exactly the three semantic fields
`agreementStatus`, `riskMismatchStatus`, and `scoreDeltaBucket` without `comparisonType` and without
`comparedEngineIds` are normalized at the common-events comparison deserialization boundary to
`RULES_VS_ML` and `["rules.primary","ml.python.primary"]`, then validated by the same strict constructor rules as new
payloads. Partial identity, reversed IDs, Velocity-containing comparison IDs, or otherwise incorrect identity is
rejected rather than completed.

This is not compatibility-by-dropping. Consumers must not hide Velocity, map it to Rules or ML, or fabricate a v1 shape
from a richer future payload. The repository controls in-repository consumers, but this does not prove the absence of
external consumers. The compatibility adapter is intentionally narrow and only covers the actual legacy v1 comparison
shape.
