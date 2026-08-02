# Multi-Engine Scoring Contract Boundary

Status: current multi-engine scoring contract boundary with historical FDP-101/FDP-102 notes.

## Scope

The multi-engine fraud intelligence vocabulary supports analyst-assisted review:

```text
transaction -> features -> multiple engines -> risk intelligence -> alert/case -> analyst decision
            -> feedback -> model/rules evaluation
```

Current Engine Intelligence runtime maps bounded engine outputs into `EngineIntelligenceSummary`, carries that
optional summary on `TransactionScoredEvent`, projects it in alert-service, exposes bounded read DTOs/OpenAPI, and
renders it in Analyst Console. Historical FDP-101 tightened the pre-exposure shared `FraudEngineResult` contract.
Historical FDP-102 added an internal alert-service feedback dataset-export foundation for offline evaluation input
only. FDP-104 added an offline Model Card v1 governance artifact that consumes FDP-103 aggregate evaluation reports
only.

These contracts still do not alter baseline scoring selection, dashboards, model retraining, thresholds, automatic
decisioning, recommendations, payment authorization, alert severity, or fraud-case status. Public/operator-triggered
export also requires authorization, sensitive-read audit, rate limits, privacy review, and retention policy.

## Declared Engine Categories

| Engine | Direction |
| --- | --- |
| Java rules engine | Produces explainable rule-driven risk context. |
| Python ML engine | Produces model risk context and bounded explanations; it is not a final decision source. |
| Velocity engine | FDP-129 optional Java diagnostic engine for transaction-rate and rapid PLN burst context; disabled by default and not a decision source. |
| Device risk engine | Declared device-context risk category; not integrated by FDP-101. |
| Merchant risk engine | Declared merchant-context risk category; not integrated by FDP-101. |
| Graph risk engine | Declared relationship-context risk category; not integrated by FDP-101. |

## FDP-129 Velocity Boundary

FDP-129 adds exactly one optional Velocity implementation: `velocity.primary` with `engineType=VELOCITY`,
`engineLanguage=java`, `version=velocity-v1`, and `required=false`. It is enabled only by
`fraud.scoring.engines.velocity.enabled=true` inside the disabled-by-default diagnostic runtime. The producer
emission flag remains `fraud.scoring.events.engine-intelligence.emit-enabled`; emission controls publication,
Velocity enablement controls registration inside that diagnostic runtime.

Velocity consumes factual bounded feature snapshot inputs only: `recentTransactionCount`,
`recentTransactionCountWindow`, `recentAmountSumPln`, and `transactionVelocityPerMinute`.
Velocity V1 requires `recentTransactionCountWindow=PT1M`; producer meaning, consumer validation, and policy meaning
all use that one-minute observation window. Changing the window is a versioned contract change, not a configuration
override.
Velocity validates count/window/rate consistency: when count, window, and per-minute rate are present, the rate must
match the PT1M count within bounded tolerance. Inconsistent present values degrade Velocity with
`VELOCITY_FEATURES_INCONSISTENT` rather than silently choosing one fact over another.
`rapidTransferFraudCaseCandidate` remains an upstream compatibility feature for existing consumers, but Velocity does
not use it as a scoring input or validation oracle. Rapid-transfer minimum count and PLN threshold semantics are owned
by `FraudFeatureThresholdContract`.

Velocity score is a deterministic normalized risk-severity signal. It is not a calibrated fraud probability and must
not be interpreted as model confidence. It is not a payment authorization decision or threshold
recommendation. Velocity V1 confidence is `UNKNOWN`.
The orchestrator owns published engine-result `latencyMs` and `generatedAt`.
The reachable Velocity V1 score table is: rapid burst plus high rate -> `0.95`, rapid burst -> `0.80`, high rate ->
`0.75`, high amount activity -> `0.50`, and valid baseline -> `0.10`. `RECENT_TRANSACTION_SPIKE` is not emitted by
Velocity V1 because the PT1M count threshold is the same official fact as the per-minute rate threshold.

The current public engine-intelligence contract allows three known engine identities: `rules.primary`,
`ml.python.primary`, and `velocity.primary`. Rules and ML are required. Velocity is an optional third diagnostic
engine. Device, Merchant, Graph, or any fourth engine requires an intentional versioned contract update. Velocity
cannot authorize, block, create cases, alter final decision source, change thresholds, or change analyst recommended
actions.

## Shared Contract Boundary

`FraudEngineResult` represents the output of one engine. It uses the existing platform `RiskLevel` enum and adds
bounded engine identity, status, confidence, reason codes, contribution descriptions, evidence descriptions,
latency, model identity, status reason code, and generation time.

The contract is bounded by both string length and collection size:

| Field | Maximum items |
| --- | ---: |
| `reasonCodes` | 10 |
| `contributions` | 10 |
| `evidence` | 10 |

Reason codes are stable machine-readable identifiers, not descriptions. Producers must not put customer
identifiers, raw payloads, exception text, account or card data, or secrets in them. `statusReason` uses an even
narrower uppercase reason-code form. Legacy reason codes may contain the word `METADATA` only as explicitly
allowlisted bounded machine-readable reason codes. This does not allow metadata bags, arbitrary metadata maps, raw
metadata payloads, metadata fields, or unbounded metadata values in the contract.

`FraudEngineContribution.feature` is a required UPPER_SNAKE machine code. Contribution `direction` is the semantic
source of truth; `weight` is diagnostic only, finite, bounded from `-1.0000` through `1.0000`, and consistent with
direction. Positive weights increase risk, negative weights decrease risk, zero is neutral, and `UNKNOWN` direction
does not carry a weight.

`FraudEngineEvidence.title`, `FraudEngineEvidence.description`, and `FraudEngineContribution.value` are safe bounded
summaries only for display. Producers must not put raw feature vectors, raw request or response payloads, customer,
account, card, device, or merchant identifiers, exception text, stack traces, tokens, secrets, endpoints, internal
hostnames, decisioning instructions, training labels, ground truth, or feedback dataset values in them. Validation
rejects basic unsafe content; it is not a full data-loss-prevention control. These fields are not raw evidence
channels, ML explanation dump channels, debug channels, exception channels, or payload channels. Future richer
explainability requires a separate scoped contract.

`FraudEngineEvidence.source` is a bounded uppercase machine-readable origin code, such as `RULES` or `ML_MODEL`,
not a description, hostname, service name, endpoint, or channel for operational details.

An engine result is not a final banking decision, not automatic blocking, and not core banking authorization.

## Status Semantics

| Status | Score and risk level | Confidence | Status reason |
| --- | --- | --- | --- |
| `AVAILABLE` | Both required | `LOW`, `MEDIUM`, or `HIGH` | Not permitted |
| `UNAVAILABLE` | Both absent | `UNKNOWN` | Required |
| `TIMEOUT` | Both absent | `UNKNOWN` | Required |
| `SKIPPED` | Both absent | `UNKNOWN` | Required |
| `DEGRADED` | Both absent or both present | `LOW`, `MEDIUM`, or `UNKNOWN` | Required |
| `FALLBACK_USED` | Both absent or both present | `LOW`, `MEDIUM`, or `UNKNOWN` | Required |

`statusReason` is a bounded machine-readable explanation of a non-available, skipped, timeout, degraded, or
fallback-used status. Only `FALLBACK_USED` declares that an actual fallback occurred; `UNAVAILABLE`, `TIMEOUT`,
`SKIPPED`, and `DEGRADED` do not imply fallback behavior.

`statusReason` is the canonical serialized JSON field. `fallbackReason` is accepted only as a JSON input alias for
backward compatibility and is not serialized as output.

`engineLanguage` is canonical lowercase, including `java`, `python`, or `other`.
Contribution direction and evidence type/status are closed contract enums rather than free-form labels.
`score` and `weight` remain `Double` in Java for compatibility, but JSON producers must emit finite bounded values
with at most four decimal places. Neither value is a decimal-precision financial amount, calibrated platform
probability, or decision signal. Missing `score` does not mean zero, and missing `riskLevel` does not mean `LOW`.
`BigDecimal` may be considered in a future breaking contract cleanup if needed.

## Existing Scoring Evidence Boundary

`FraudEngineEvidence` and its controlled vocabulary belong to the unintegrated engine-result contract. They do not
replace, extend, or project the existing `ScoringEvidenceItem` carried by `TransactionScoredEvent`.

Likewise, engine `reasonCodes` are safe machine-readable identifiers at this foundation boundary; they are not
silently promoted into the existing platform `ReasonCode` taxonomy. Any event integration must define explicit
mapping and compatibility policy before those concepts can cross the existing scoring-evidence boundary.

## Compatibility Policy

`FraudEngineResult` is not added as `engineResults[]` to `TransactionScoredEvent` or any other Kafka event. Current
event exposure uses the bounded optional `TransactionScoredEvent.engineIntelligence` summary. Historical FDP-101 did
not add new `ScoringContext`, `FraudSignalEngine`, scoring orchestration, alert projection, API, OpenAPI, UI, feedback
dataset, dataset export, model retraining, rule update, or export integration. Historical FDP-102 added only an
internal alert-service dataset export foundation and did not expose a public API, OpenAPI path, UI, scheduled job, CLI
job, Python runner, Kafka event field, scoring behavior change, model evaluation runner, retraining, promotion, or
threshold switch. No public `engineResults[]` event field is added.

Producers remain strict and emit only documented fields. Consumers tolerate unknown additive fields in the engine
result, contribution, and evidence records while still validating all known fields. Unknown fields are ignored,
not interpreted as decision signals. Breaking semantic changes require versioning.

## Producer Responsibilities Before Emitting FraudEngineResult

Validation in this contract blocks obvious unsafe content only. It is not DLP and must not be marketed or relied
upon as DLP.

A producer must:

- sanitize explanation summaries before constructing `FraudEngineResult`;
- map raw model or runtime errors to bounded `statusReason` codes;
- never pass raw exception messages, stack traces, hostnames, URLs, request or response payloads, feature vectors,
  customer identifiers, account or card data, tokens, secrets, or authorization headers;
- emit documented fields only;
- treat unknown fields received by tolerant consumers as ignored extensions, never risk signals;
- define and test any mapping from engine `reasonCodes` to the existing platform `ReasonCode` taxonomy.

## FDP-102 Feedback Dataset Export Boundary

The FDP-102 dataset export uses `EngineIntelligenceFeedbackDatasetExportResult` as an internal wrapper. It declares
`FEEDBACK_SUBMITTED_AT` as the single time basis, filters `fromInclusive <= submittedAt <= toInclusive`, sorts by
`submittedAt DESC, feedbackId ASC`, caps date range at 31 days, caps `maxRecords` from 1 through 500, and deduplicates
by pseudonymous `transactionReference` with the newest submitted feedback winning.

The JSONL representation starts with an `EXPORT_METADATA` line containing truncation, count, time-basis,
deduplication, and failure metadata; each subsequent line is one `DATASET_RECORD`. Analyst feedback is not ground
truth and not a model training label. `CONFIRMED_FRAUD` maps to `ANALYST_CONFIRMED_FRAUD`, `MARKED_LEGITIMATE` maps to
`ANALYST_MARKED_LEGITIMATE`, and inconclusive, needs-more-info, missing, or unknown decisions map to
`NOT_EVALUATION_ELIGIBLE`. Missing ML/rules scores are not zero, missing risks are not `LOW`, and missing projection
is explicit rather than no-fraud evidence. Failed export JSONL with `failureReason != null` is a hard failure, not a
successful empty dataset.

## Integration Preconditions

Before any later integration branch emits or projects `FraudEngineResult`, it must supply:

- producer sanitization tests;
- a `statusReason` mapping table;
- a bounded event payload test;
- an old-event compatibility test;
- a no raw payload or feature leak test;
- scenario tests for unavailable, timeout, skipped, and degraded engine results.

## Out Of Scope

Current Engine Intelligence still does not expose raw `FraudEngineResult`, add public `engineResults[]`, change
baseline scoring selection, retrain models, update rules, create platform aggregation as a final decision, recommend
analyst actions, authorize payments, approve/decline/block transactions, or perform automatic decisioning.
Historical FDP-101 itself also did not add scoring context, engine wrappers, orchestration, comparison behavior, event
integration, projections, API surface, UI, feedback evaluation, or dataset export.
