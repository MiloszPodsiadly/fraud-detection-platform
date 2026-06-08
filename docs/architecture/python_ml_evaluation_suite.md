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

Failed FDP-102 exports abort evaluation. In other words, failed FDP-102 exports abort evaluation. A metadata line with `failureReason != null` is a failed export, not a
successful empty dataset and not a zero-record evaluation input.

## Label Semantics

Analyst labels are evaluation signals, not ground truth. In other words, analyst labels are evaluation signals, not ground truth. They are not model training labels, final decisions, payment
decisions, or automatic decisioning signals.

Allowed labels:

- `ANALYST_CONFIRMED_FRAUD` is the evaluation-positive label.
- `ANALYST_MARKED_LEGITIMATE` is the evaluation-negative label.
- `NOT_EVALUATION_ELIGIBLE` is excluded from model-quality metrics and is never treated as negative.

NOT_EVALUATION_ELIGIBLE is excluded from model-quality metrics.

## Missing Data

Missing ML/rules/projection is explicit. Missing ML score is not zero. Missing ML risk is not `LOW`. Missing rules
score is not zero. Missing rules risk is not `LOW`. Missing projection is counted separately and does not mean no
fraud.

FDP-102 currently supplies risk and score buckets rather than raw numeric ML scores. FDP-103 therefore uses documented
bucket-based ordering for ranking diagnostics: higher ML risk or score buckets first, then deterministic
`evaluationRecordId` tie-break. It does not invent raw scores.

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
