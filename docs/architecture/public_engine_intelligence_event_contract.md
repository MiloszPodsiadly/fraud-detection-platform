# Public Engine Intelligence Event Contract

Status: current public Engine Intelligence event contract.

## Purpose

The public Engine Intelligence event is a safe, bounded, optional
`TransactionScoredEvent.engineIntelligence` summary. The current implementation wires disabled-by-default producer
emission, alert-service projection, bounded API/OpenAPI, and Analyst
Console rendering without making engine intelligence a final decision source.

## Public Contract Boundary

The producer does not publish the internal aggregation model 1:1. Public engine intelligence is an
allowlisted projection of internal aggregation semantics. `FraudEngineAggregationResult` is
internal and must not be serialized directly. The public event contract is smaller and more stable
than the internal model. This is a separate public event contract.

## Internal-To-Public Mapping Policy

`PublicEngineIntelligenceMapper` defines a deterministic mapping from internal aggregation semantics
to the public DTOs. It is called only for disabled-by-default producer diagnostic enrichment.

## Versioning Strategy

`EngineIntelligenceSummary.contractVersion` is required and equals `1`. A future incompatible
shape requires explicit compatibility review and a new contract version.

## Backward Compatibility Rules

`TransactionScoredEvent.engineIntelligence` is optional. Old producers may omit it, and old event
JSON remains valid. A missing summary does not mean safe, low risk, or zero score. Producer
wiring requires a consumer-first rollout: consumers must deploy the compatible contract before any
producer emits `engineIntelligence`, because historical consumers may reject an unknown top-level
field.

Compatibility is intentionally narrow. A valid current event may omit `engineIntelligence` or carry explicit null,
which is accepted as absence and does not invent model lineage. When a summary is present, its comparison identity must
be complete and explicit regardless of the outer event `modelVersion`. Historical identity-free comparisons, partial
comparison identity, summaries missing Rules or ML, incorrect engine ordering, unsupported IDs, and malformed current
canonical values are rejected or fail closed; the read boundary does not repair them.

`TransactionScoredEvent.mlPredictionEvidence` is a separate optional internal field. Every current scored event requires
exactly one of it or `mlPredictionEvidenceOmissionReason`; events with neither fail deserialization. Legacy null/null
messages must be drained, migrated from authoritative evidence, archived, or quarantined before current consumers read
them, and consumers must not invent or backfill evidence.
Pipeline-level reasons (`DIAGNOSTIC_EMISSION_DISABLED` and `DIAGNOSTIC_ENRICHMENT_UNAVAILABLE`) require the summary
to be absent. ML-engine-derived reasons require an observed `ml.python.primary` result and cannot be used to describe
an unavailable diagnostic pipeline; `ML_ENGINE_UNAVAILABLE` is reserved for observed operational ML statuses.
When present, it requires a matching `AVAILABLE` `ml.python.primary` entry in `engineIntelligence`, complete identical
model identity, matching risk level and status, canonical engine identity, supported evidence version, finite exact
score, matching forward-derived public score bucket, and source execution timestamp. This check maps the exact score
to its canonical bucket; it never reconstructs an exact score from a bucket. Invalid evidence fails event deserialization; it is not silently reduced to a
bucket or detached from its summary. The exact field is not part of the public Engine Intelligence API/UI DTO.

## Payload Limits

The public payload allows at most three engines, five diagnostic signals, ten warning summaries, five
reason codes per engine, and 128 characters per bounded string.

Version 1 allows three known engine identities: `rules.primary`, `ml.python.primary`, and `velocity.primary`. Rules
and ML are required. Velocity is an optional third diagnostic engine. Missing Rules or ML in a current
`contractVersion=1` summary is corruption, not an operational representation. Operational failure is represented by a
present engine result with a non-AVAILABLE status and bounded reason code, not by omitting the engine.

Public timestamps use canonical UTC RFC3339 with uppercase `Z`, valid calendar dates from year `0001` through `9999`,
hour `00-23`, second `00-59`, and optional fractional seconds from 1 through 9 digits. Offsets, timezone-less strings,
leap seconds, `24:00`, year `0000`, and longer fractions are invalid.

## Public Field Allowlist

The public shape contains only contract version, timestamp, bounded engine summaries, comparison
metadata, diagnostic signals, and warning code counts. Engine identities and reason codes use
allowlists.

An `AVAILABLE` `ml.python.primary` result must include a bounded `modelIdentity` object with `modelName`, `modelVersion`,
and `featureContractVersion`. This identity belongs to the ML engine-intelligence result, not to the top-level final
scoring fields on `TransactionScoredEvent`. Rules, Velocity, and non-AVAILABLE ML engine results must omit it. A current
identity-free AVAILABLE ML result is malformed and fails closed; readers do not invent lineage or rewrite the engine
to another operational status. Previously stored feedback rows with missing lineage remain historical data and are
classified as `MODEL_LINEAGE_UNAVAILABLE` and excluded from model-specific evaluation.

### Deployment Treatment For Historical Projections

Before deploying the strict reader, inventory Mongo `engine_intelligence_projections` documents containing an
`AVAILABLE` `ml.python.primary` engine without complete `modelIdentity`. Such documents do not satisfy the current read
contract: archive them under the approved retention policy or rebuild the projection only from an authoritative event
that already contains complete lineage. Do not synthesize identity from the currently loaded model, registry state, or
deployment configuration. Existing feedback records keep their original missing-lineage evidence, remain classified as
`MODEL_LINEAGE_UNAVAILABLE`, and stay excluded from exact-model evaluation; the runtime does not normalize them merely
to make a historical projection displayable.

## Field Omission Rules

The public DTOs omit raw payloads, identifiers, endpoints, tokens, secrets, stack traces, exception
text, raw contribution values, internal objects, and decisioning fields.

## Score Exposure Decision

Score is bucketed or omitted, not raw, unless explicitly approved. A score bucket is diagnostic,
not a calibrated probability and not a final score. Score delta is also bucketed and is not
calibration proof. For v1, score delta applies only to `RULES_VS_ML` comparison identity:
`comparedEngineIds=["rules.primary","ml.python.primary"]`. Velocity is not included in score-delta semantics.
For v1, available scores map to `LOW` for `0.00-0.25`, `MEDIUM` for
`>0.25-0.50`, `HIGH` for `>0.50-0.75`, and `VERY_HIGH` for `>0.75-1.00`. `NONE` is reserved for an
explicitly omitted value and is not a missing-score fallback. Comparable score deltas map to `NONE`
for exact zero, `SMALL` for `>0.00-0.15`, `MEDIUM` for `>0.15-0.35`, and `LARGE` for `>0.35-1.00`.
For score buckets, `NONE` does not mean score zero and does not mean a missing score. Missing score
maps to `UNAVAILABLE`.

The internal evidence field is the narrow exception inside the Kafka event, not an expansion of this public
summary. It preserves the exact diagnostic ML score from the same `FraudEngineResult` so governed backend evaluation
does not reverse-map a bucket. The top-level `fraudScore` remains the platform result and can legitimately differ.

## Confidence Exposure Decision

`confidence` is an explicit engine output field, not a value inferred by the public mapper. Public
available engine results may carry `UNKNOWN` confidence when no authoritative calibration policy is
available. Consumers must not infer confidence from score bucket, risk level, reason codes, engine
type, or availability status.

## Evidence Exposure Decision

Evidence free-text descriptions are omitted or templated, not raw. Contract version 1 also omits evidence
titles and display text.

## Diagnostic Signal Exposure Decision

Diagnostic signals are bounded public projections. Diagnostic signals are not recommendations,
final explanations, payment decision rationale, or proof of fraud.

## Timeout/Unavailable/Degraded Semantics

Timeout does not mean low risk. Missing score does not become zero. Missing risk does not become
LOW. Non-AVAILABLE engine statuses must not carry public `riskLevel`. For `TIMEOUT`, `UNAVAILABLE`,
`DEGRADED`, `SKIPPED`, and `FALLBACK_USED`, `riskLevel` is omitted. Public consumers must not infer
LOW risk from missing `riskLevel` or from an `UNAVAILABLE` score bucket. Timeout, unavailable,
degraded, skipped, and fallback-used engine score buckets are `UNAVAILABLE`. Operational diagnostic
signals must not carry fraud risk or fraud score buckets.

## No Final Decisioning

Rules-vs-ML agreement is not approval. Rules-vs-ML disagreement is not decline. Risk mismatch is not final decision.
The event contract does not add final decisioning.

## Non-Goals

The public event contract does not expose raw `FraudEngineResult`, raw feature vectors, internal aggregation objects,
final decisioning, payment authorization, automatic approve/decline/block behavior, or generic all-engine comparison.
The current alert-service projection, API/UI, and controlled producer publication are separately owned boundaries.

## Consumer-First Rollout Guard

The public contract was deployed before runtime emission. Producer diagnostic enrichment remains disabled by default
and follows a consumer-first rollout.
Historical consumers may reject unknown top-level fields, so emission must remain explicitly
controlled and required consumers must remain compatible with the current contract.

Producer mapping must use `PublicEngineIntelligenceMapper` or an explicitly reviewed equivalent.
Producer mapping must preserve timeout does not mean low risk, missing score does not become zero,
missing risk does not become LOW, operational statuses do not carry `riskLevel`, operational
diagnostic signals do not carry fraud score buckets, agreement is not approval, disagreement is not
decline, and diagnostic signals are not recommendations.

Producer rollout must not add final decisioning. Any future change to projection, API/UI, or event semantics must be
explicitly scoped and reviewed rather than hidden inside producer rollout. Producer rollout does not itself own downstream
projection, API/UI, or final decisioning.
