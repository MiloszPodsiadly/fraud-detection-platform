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

Agreement requires comparable available engine results. Agreement is not proof of fraud.
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
produce fraud signals. Timeout/unavailable/degraded engine reason codes produce operational signals,
not fraud signals. Raw descriptions and raw feature values are excluded.

## Evidence Truncation

Raw evidence is not propagated. Evidence is bounded and truncated by policy. Evidence count, title
length, and description length are capped deterministically.

## Size Limits

Result size is bounded. Policy limits cap engine results, reason codes, evidence summaries,
contribution summaries, strongest signals, warnings, and text lengths.

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
