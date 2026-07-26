# Python ML Evaluation Suite

Status: Offline evaluation foundation. FDP-124/FDP-126 is the active path for FDP-123 feedback dataset evaluation and Shadow Performance contract artifacts. The previous FDP-102/FDP-103 executable path has been removed.

## Scope

The current evaluation suite is offline-only. It consumes FDP-123 bounded feedback dataset JSONL and builds aggregate diagnostic
artifacts for platform recommendation review. It does not read production DBs, does not read raw payloads, does not call
production scoring, does not retrain models, does not promote models, does not change thresholds, does not change production scoring,
does not emit Kafka events, does not expose API/UI, does not recommend analyst actions, and does not authorize
payments.

FDP-124 adds a separate offline/internal path for FDP-123 feedback dataset JSONL. FDP-124 consumes FDP-123 `DATASET_RECORD` rows from the schema-backed envelope at `docs/schemas/feedback_dataset_record.schema.json`.
`DATASET_METADATA` is not an evaluation row. FDP-124 does not train models, does not promote models, does not
recommend production threshold changes, does not alter scoring, payment, workflow, or case behavior, and does not
publish reports automatically.

`offline_evaluation.fdp123.run_fdp123_evaluation` is a manual local offline runner. It is not a scheduler, not automatic report publishing, not a public export endpoint, and not runtime server integration. Generated artifacts are
local/internal diagnostic artifacts. External publishing requires a separate security and governance review.

The package lives under `ml-inference-service/offline_evaluation`. It has no network calls, database connectors,
Kafka clients, production service clients, scheduled jobs, endpoints, UI files, model artifact mutation, threshold
mutation, retraining module, or promotion workflow.

## Input Contract

The only supported active input is FDP-123 JSONL:

```text
{"type":"DATASET_METADATA", ...}
{"type":"DATASET_RECORD", "record": {...}}
```

The first non-empty line must be `DATASET_METADATA`. Metadata is required, dataset records must follow metadata,
unknown line types are rejected, malformed JSONL is rejected, and multiple metadata lines are rejected. Safe unknown
optional metadata or record fields are ignored. Invalid known fields fail validation.

Failed FDP-123 dataset builds abort evaluation. A metadata line with `failureReason != null` represents a failed
dataset build and must not be treated as an empty successful dataset.

FDP-123/FDP-124 is not a permissive dual-format parser, and labels are not mixed across historical contracts.

The FDP-123/FDP-124 label mapping is limited to:

- `POSITIVE_FRAUD` as the positive class.
- `NEGATIVE_LEGITIMATE` as the negative class.

FDP-124 treats analyst feedback labels as bounded evaluation signals only. They are not ground truth, model-training
labels, final bank decisions, payment decisions, or automatic decisioning signals. Pseudonymous references are not
anonymization and remain internal parsing/report references only.

FDP-124 fails fast on malformed or invalid schema input. Malformed-record exclusion counters are reserved for a
future tolerant evaluation mode and remain zero for successful reports. Invalid known fields, missing required
fields, unsafe values, inconsistent metadata, overlong lines, and inputs beyond the bounded FDP-123 limits abort
evaluation before a successful report is generated.

## Label Semantics

Analyst labels are evaluation signals only. They are not ground truth, model-training labels, final decisions,
payment decisions, or automatic decisioning signals.

Allowed labels:

- `POSITIVE_FRAUD` is the evaluation-positive label.
- `NEGATIVE_LEGITIMATE` is the evaluation-negative label.

## Missing Data

Missing ML/rules/projection is explicit. Missing ML score is not zero. Missing ML risk is not `LOW`. Missing rules
score is not zero. Missing rules risk is not `LOW`. Missing projection is counted separately and does not mean no
fraud.

FDP-124 treats engineStatus as the source of truth for operational availability. For non-AVAILABLE engine statuses,
risk and score bucket fields must be absent. `UNAVAILABLE`, `TIMEOUT`, `SKIPPED`, `DEGRADED`, and `FALLBACK_USED`
are not ranked and are not high/low signals.

FDP-123 currently supplies risk and score buckets rather than raw numeric ML scores. FDP-124 therefore uses documented
bucket-based ordering for ranking diagnostics: higher ML risk or score buckets first, then deterministic
`evaluationRecordId` tie-break. It does not invent raw scores.

FDP-124 accepts FDP-123 pseudonymous input references only for parsing and deterministic ordering. Reports are
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

The previous FDP-102/FDP-103 Platform Recommendation Evaluation Card path has been removed. Platform Recommendation Evaluation Card v1 is now implemented only by the
FDP-123/FDP-124/FDP-126 path described below.

The report writer does not emit per-record output by default and does not emit raw transaction IDs, customer/account/
card/device/merchant identifiers, analyst IDs, submitted-by values, correlation IDs, idempotency keys, request hashes,
raw payloads, raw feature vectors, raw evidence, raw ML requests or responses, endpoints, tokens, secrets, stack
traces, exception messages, ground truth labels, model training labels, final decisions, payment authorization,
promotion signals, threshold recommendations, or analyst recommendations.

Removed FDP-103 budget/top-k metric aliases are not part of current Shadow Performance Summary v2, Evaluation Card v1,
or Promotion Review Readiness contracts. Current FDP-124/FDP-126 artifacts expose bounded aggregate metric objects and
metric availability instead of fake zeroes or legacy aliases.

FDP-124 reports are deterministic local artifacts for offline review. They include dataset summary, class balance,
alertRecommended confusion matrix, risk-level breakdown, fraud-score bucket analysis, precision@K, recall@K, and a
bounded disagreement report. Division by zero produces unavailable values rather than fake zeroes. Empty datasets,
single-class datasets, missing scores, missing alert recommendations, missing risk levels, truncation, and small sample
sizes are surfaced as warnings. Only FDP-123 `DATASET_RECORD` lines are metric rows. Low sample size warnings are not model-quality conclusions.

FDP-124 report sets use a manifest-last local artifact pattern. The writer prepares report payloads in memory, writes
temporary artifact files, writes `manifest.json.tmp`, replaces report artifacts, and replaces `manifest.json` last. A report set is considered complete only when `manifest.json` exists, has report type
`FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1`, lists the expected artifact files, and each listed artifact matches
the manifest `sha256` and `sizeBytes`. The manifest is not external publishing, and scheduled generation or external
publication requires a separate security, governance, and observability review.

FDP-124 report artifacts are not external exports and do not expose raw source identifiers, raw notes, raw payloads,
raw evidence, feature vectors, ground-truth fields, training labels, final decisions, payment authorization, model
promotion signals, or production threshold recommendations.

FDP-124 disagreement rows may include `decisionReasonCodes` because FDP-123 validates them as bounded machine-code
values. They are allowed only in local/internal disagreement rows. They are not notes, not raw evidence, and must not
contain raw IDs, free text, payloads, tokens, or secrets.

FDP-123/FDP-124 Platform Recommendation Evaluation Card v1 is the single executable Platform Recommendation Evaluation Card implementation. It is a local/internal governance
artifact generated from FDP-124 aggregate artifacts only. It accepts only canonical `manifest.json` and
`evaluation_summary.json` inputs from the FDP-124 artifact set, validates
`reportType = FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1` and
`artifactSetVersion = fdp123-report-artifact-set-v1`, bounds input size before full read, and preserves manifest
`sha256` and `sizeBytes` checks. FDP-124 owns `evaluationSubject`, `metricsSubject`, and `metricBasis`; Platform Recommendation Evaluation Card v1
copies those values and rejects caller-controlled model identity. Its `metricBasis` is
`ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK`, so `alertRecommendedConfusionMatrix` is a platform recommendation
diagnostic rather than direct model performance. It does not read `disagreement_report.jsonl` in v1, does not copy
FDP-124 `disagreementSummary` into Platform Recommendation Evaluation Card v1, does not read the raw FDP-123 dataset, and does not expose raw IDs
or per-record data. Its evidence counts use the FDP-123 `MAX_DATASET_RECORDS = 1000` limit. Its binary class-count invariant is
`positiveClassCount + negativeClassCount == recordsEvaluated`. Its published governance timestamps are real RFC3339
UTC `Z` date-times with optional 1-6 digit fractional seconds, and checked so the Platform Recommendation Evaluation Card `generatedAt` instant is not earlier than the
evaluation evidence instant. `sourceManifestSha256` is only a local lineage and integrity fingerprint, not a
signature, notarization, external attestation, immutability guarantee, or independent trust anchor. The source manifest
SHA-256 is not a signature, notarization, external attestation, immutability guarantee, or independent trust anchor. It is
not model promotion, not production approval, not threshold recommendation, not payment authorization, and not workflow or
case automation. Its `allowedUsageModes` values are documentation semantics only, not runtime permissions. The evaluation
card records `evaluationPurpose = OFFLINE_DIAGNOSTIC` and all runtime, promotion, threshold, payment, and workflow
authority fields as `NONE`.
