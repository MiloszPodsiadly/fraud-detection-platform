# Public Engine Intelligence Event Contract

Status: FDP-92 contract-only foundation.

## Purpose

FDP-92 defines a safe, bounded, backward-compatible public engine intelligence event. It adds an
optional nested `engineIntelligence` field to `TransactionScoredEvent` without changing current
scoring behavior or downstream consumption.

## Public Contract Boundary

FDP-92 does not publish the internal aggregation model 1:1. Public engine intelligence is an
allowlisted projection of internal aggregation semantics. `FraudEngineAggregationResult` is
internal and must not be serialized directly. The public event contract is smaller and more stable
than the internal model. This is a separate public event contract.

## Internal-To-Public Mapping Policy

The unwired `PublicEngineIntelligenceMapper` defines a deterministic mapping design from FDP-91
aggregation semantics to the public DTOs. Runtime event publishing does not call it in FDP-92.

## Versioning Strategy

`EngineIntelligenceSummary.contractVersion` is required and equals `1`. A future incompatible
shape requires explicit compatibility review and a new contract version.

## Backward Compatibility Rules

`TransactionScoredEvent.engineIntelligence` is optional. Old producers may omit it, and old event
JSON remains valid. A missing summary does not mean safe, low risk, or zero score. FDP-93 producer
wiring requires a consumer-first rollout: consumers must deploy the FDP-92 contract before any
producer emits `engineIntelligence`, because historical consumers may reject an unknown top-level
field.

## Payload Limits

The public payload allows at most two engines, five diagnostic signals, ten warning summaries, five
reason codes per engine, and 128 characters per bounded string.

## Public Field Allowlist

The public shape contains only contract version, timestamp, bounded engine summaries, comparison
metadata, diagnostic signals, and warning code counts. Engine identities and reason codes use
allowlists.

## Field Omission Rules

The public DTOs omit raw payloads, identifiers, endpoints, tokens, secrets, stack traces, exception
text, raw contribution values, internal objects, and decisioning fields.

## Score Exposure Decision

Score is bucketed or omitted, not raw, unless explicitly approved. A score bucket is diagnostic,
not a calibrated probability and not a final score. Score delta is also bucketed and is not
calibration proof. For v1, available scores map to `LOW` for `0.00-0.25`, `MEDIUM` for
`>0.25-0.50`, `HIGH` for `>0.50-0.75`, and `VERY_HIGH` for `>0.75-1.00`. `NONE` is reserved for an
explicitly omitted value and is not a missing-score fallback. Comparable score deltas map to `NONE`
for exact zero, `SMALL` for `>0.00-0.15`, `MEDIUM` for `>0.15-0.35`, and `LARGE` for `>0.35-1.00`.
For score buckets, `NONE` does not mean score zero and does not mean a missing score. Missing score
maps to `UNAVAILABLE`.

## Evidence Exposure Decision

Evidence free-text descriptions are omitted or templated, not raw. FDP-92 v1 also omits evidence
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

Agreement is not approval. Disagreement is not decline. Risk mismatch is not final decision.
FDP-92 does not add final decisioning.

## Non-Goals

FDP-92 does not add alert-service projection. FDP-92 does not add API/UI. FDP-92 does not wire the
public mapper into production scoring or event publication.

## Future FDP-93 Projection Scope

Future FDP-93 projection scope may add separately reviewed producer wiring and downstream
consumption. FDP-92 intentionally stops at the public contract and mapping design boundary.
