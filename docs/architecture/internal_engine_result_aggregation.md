# Internal Engine Result Aggregation and Comparison

Status: FDP-91 internal-only comparison foundation.

FDP-91 defines internal comparison semantics before engine intelligence is published externally.
FDP-91 is internal-only. It adds no `TransactionScoredEvent.engineResults[]`, no Kafka schema
changes, no alert-service projection, no API/UI, and no final decisioning. FDP-91 does not
approve/decline/block.

## Aggregation Model

`FraudEngineAggregationService` consumes an internal `FraudScoringOrchestrationResult` and produces
an internal `FraudEngineAggregationResult`. The service is not wired into production scoring.
Timeout/unavailable/degraded engines remain visible.

## Normalized Engine Result

`NormalizedFraudEngineResult` retains bounded internal engine identity, status, score, risk,
confidence, normalized reason codes, sanitized evidence summaries, sanitized contribution
summaries, and latency. Timeout does not mean low risk. Missing score does not become zero. Missing
risk does not become low risk.

## Agreement Semantics

Agreement requires comparable available engine results and means exact risk-level alignment between
those engines. Agreement is not proof of fraud. Adjacent risk variance means comparable engines
differ by one risk level. Adjacent risk variance is internal diagnostic metadata, not final
decisioning. Material risk mismatch means comparable engines differ by more than one risk level.
Disagreement is not final decision. Optional operational failures produce partial comparison
metadata. Required engine operational failures remain explicitly not comparable.

## Risk Mismatch Semantics

Risk mismatch is calculated only for comparable available engines with risk levels. Missing risk
does not become low risk. Adjacent and material mismatches remain internal diagnostics.

## Score Delta Semantics

Score delta is the absolute difference between two comparable available scores. Score delta is not
calibration proof. Missing score does not become zero. Timeout/unavailable/degraded results are not
comparable scores.

## Strongest Signal Extraction

Strongest signals are internal diagnostics, not recommended actions. Available engine reason codes
with a risk level produce fraud signals. Available engines without a risk level and
timeout/unavailable/degraded engine reason codes produce operational signals, not fraud signals.
Strongest signals are strongest by bounded internal diagnostic ordering: signal category, risk
severity, score, engine-order tie-breaker, and reason code. Strongest signals are not final
decisioning. Raw descriptions and raw feature values are excluded.

## Evidence Truncation

Raw evidence is not propagated. Evidence is bounded and truncated by policy. Evidence count, title
length, and description length are capped deterministically.

## Size Limits

Result size is bounded. Policy limits cap engine results, reason codes, evidence summaries,
contribution summaries, strongest signals, warnings, and text lengths.
Warning counts may be derived internally from bounded warning codes and must not include raw text.

## FDP-91 Engine Pair Boundary

FDP-91 compares only the allowlisted pair rules.primary and ml.python.primary. FDP-91 is not an
N-way multi-engine comparison framework. Adding Velocity, Device, Merchant, or any future engine
requires a separate branch and explicit semantic review.

Adding a new engine requires updates to the engine allowlist, aggregation policy, deterministic
ordering, score delta semantics, risk mismatch semantics, agreement semantics, strongest signal
semantics, docs governance, and runtime isolation tests. Unknown engine IDs fail fast and are not
published externally.

## Safety And Leakage Prevention

High-cardinality identifiers are forbidden. Raw transaction/customer/account/card/merchant IDs,
payloads, feature values, ML vectors, endpoints, tokens, secrets, stack traces, and exception text
are excluded from normalized output.

## Out Of Scope

FDP-91 adds no event extension, Kafka schema change, alert-service projection, API/UI,
`CompositeFraudScoringEngine` wiring, external engine intelligence publication, final score, final risk,
winning engine, recommended action, or decision policy.

## Future FDP-92 Event Extension

FDP-91 prepares for future event extension but does not add it. Any future publication requires a
separate compatibility-reviewed branch.

## Public Contract Boundary For FDP-92

FraudEngineAggregationResult is an internal model. FDP-92 must not publish
FraudEngineAggregationResult 1:1. FDP-92 must define a separate compatibility-reviewed public
event contract. That contract must have its own schema, size limits, backward compatibility review,
and data safety review.

The public event contract must decide separately whether score, evidence title, evidence
description, contribution feature, and strongest signal should be exposed, bucketed, templated, or
omitted. Score may be exposed, bucketed, templated, or omitted. Internal aggregation types must not
be placed in common-events directly. FDP-91 prepares semantics; it does not define the public Kafka
schema.

## Strongest Signal Naming Boundary

Strongest signals are bounded internal diagnostic summaries. Strongest signals are not analyst
recommendations. Strongest signals are not final explanations. Strongest signals are not a payment
decision rationale. Strongest signals are not a global proof of fraud. Future API/UI may rename or
reshape this concept before exposing it.

## FDP-92 Readiness Checklist

Before any event/API exposure:

- define separate public DTO/event contract
- do not reuse internal aggregation record directly
- decide whether raw score is allowed, bucketed, or omitted
- decide whether evidence title/description are allowed, templated, or omitted
- decide whether contribution feature is allowed, categorized, or omitted
- use allowlist-first public schema
- enforce payload size limits
- enforce backward compatibility tests
- enforce raw leakage tests
- enforce no final decisioning
- preserve timeout/unavailable/degraded semantics
- preserve missing score does not become zero
- preserve missing risk does not become LOW
- preserve agreement/disagreement/adjacent variance semantics
