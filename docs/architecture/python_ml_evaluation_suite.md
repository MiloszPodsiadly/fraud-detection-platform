# Python ML Evaluation Suite

Status: FDP-103 offline evaluation foundation.

## Scope

FDP-103 is offline-only. FDP-103 consumes only FDP-102 bounded JSONL export input and builds aggregate diagnostic
reports for model and rules review. It does not read production DBs, does not read raw payloads, does not call
production scoring, does not retrain models, does not promote models, does not change thresholds, does not change production scoring,
does not emit Kafka events, does not expose API/UI, does not recommend analyst actions, and does not authorize
payments.

The package lives under `ml-inference-service/offline_evaluation`. It has no network calls, database connectors,
Kafka clients, production service clients, scheduled jobs, endpoints, UI files, model artifact mutation, threshold
mutation, retraining module, or promotion workflow.

## Input Contract

The only supported input is FDP-102 JSONL:

```text
{"type":"EXPORT_METADATA", ...}
{"type":"DATASET_RECORD", "record": {...}}
```

The first non-empty line must be `EXPORT_METADATA`. Metadata is required, dataset records must follow metadata,
unknown line types are rejected, malformed JSONL is rejected, and multiple metadata lines are rejected. Safe unknown
optional metadata or record fields are ignored. Invalid known fields fail validation.

Failed FDP-102 exports abort evaluation. A metadata line with `failureReason != null` represents a failed FDP-102
export. FDP-103 must abort evaluation and must not treat that input as an empty successful dataset.

FDP-103 v1 fails fast on malformed or invalid schema input. Malformed-record exclusion counters are reserved for a
future tolerant evaluation mode and remain zero for successful v1 reports. Invalid known fields, missing required
fields, unsafe values, inconsistent metadata, overlong lines, and inputs beyond the bounded FDP-102 limits abort
evaluation before a successful report is generated.

## Label Semantics

Analyst labels are evaluation signals only. They are not ground truth, model-training labels, final decisions,
payment decisions, or automatic decisioning signals.

Allowed labels:

- `ANALYST_CONFIRMED_FRAUD` is the evaluation-positive label.
- `ANALYST_MARKED_LEGITIMATE` is the evaluation-negative label.
- `NOT_EVALUATION_ELIGIBLE` is excluded from model-quality metrics and is never treated as negative.

NOT_EVALUATION_ELIGIBLE is excluded from model-quality metrics.

## Missing Data

Missing ML/rules/projection is explicit. Missing ML score is not zero. Missing ML risk is not `LOW`. Missing rules
score is not zero. Missing rules risk is not `LOW`. Missing projection is counted separately and does not mean no
fraud.

FDP-103 v1 treats engineStatus as the source of truth for operational availability. For non-AVAILABLE engine statuses,
risk and score bucket fields must be absent. `UNAVAILABLE`, `TIMEOUT`, `SKIPPED`, `DEGRADED`, and `FALLBACK_USED`
are not ranked and are not high/low signals.

FDP-102 currently supplies risk and score buckets rather than raw numeric ML scores. FDP-103 therefore uses documented
bucket-based ordering for ranking diagnostics: higher ML risk or score buckets first, then deterministic
`evaluationRecordId` tie-break. It does not invent raw scores.

FDP-103 accepts FDP-102 pseudonymous input references only for parsing and deterministic ordering. Reports are
aggregate-only and must not emit `evaluationRecordId`, `transactionReference`, `eval-`, or `txnref-` values.

Reason codes and diagnostic signals are validated as bounded machine-code values. These checks reject obvious unsafe
raw or sensitive patterns, but they are bounded safeguards, not a full DLP control.

## Reports

Reports are diagnostic aids only. They are not promotion criteria, not threshold-change criteria, and not production
approval criteria.

Generated reports are aggregate-first and include:

- input summary,
- rule-vs-ML disagreement summary,
- offline diagnostic quality metrics,
- exclusions,
- bounded warnings.

The report writer does not emit per-record output by default and does not emit raw transaction IDs, customer/account/
card/device/merchant identifiers, analyst IDs, submitted-by values, correlation IDs, idempotency keys, request hashes,
raw payloads, raw feature vectors, raw evidence, raw ML requests or responses, endpoints, tokens, secrets, stack
traces, exception messages, ground truth labels, model training labels, final decisions, payment authorization,
promotion signals, threshold recommendations, or analyst recommendations.

`precisionAtBudget` and `recallAtTopK` are offline diagnostic metrics. Because FDP-102 does not provide raw numeric
ML scores, these metrics are bucket-ordered evaluation metrics. They are not production approval criteria, not model
promotion criteria, and not threshold-change criteria.
