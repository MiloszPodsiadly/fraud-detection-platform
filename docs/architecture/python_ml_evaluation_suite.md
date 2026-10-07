# Python ML Evaluation Suite

Status: active offline Feedback Dataset Evaluation pipeline and Shadow Performance contract foundation.

## Scope

The current evaluation suite is offline-only. It consumes bounded feedback dataset JSONL and builds aggregate diagnostic
artifacts for platform recommendation review. It does not read production DBs, does not read raw payloads, does not call
production scoring, does not retrain models, does not promote models, does not change thresholds, does not change production scoring,
does not emit Kafka events, does not expose API/UI, does not recommend analyst actions, and does not authorize
payments.

Feedback Dataset Evaluation consumes feedback dataset `DATASET_RECORD` rows from the schema-backed envelope at
`docs/schemas/feedback_dataset_record.schema.json`. `DATASET_METADATA` is not an evaluation row. The evaluator does not train models, does not promote models, does not
recommend production threshold changes, does not alter scoring, payment, workflow, or case behavior, and does not
publish reports automatically.

`offline_evaluation.feedback_dataset_evaluation.run_feedback_dataset_evaluation` is a manual local offline runner. It is not a scheduler, not automatic report publishing, not a public export endpoint, and not runtime server integration. Generated artifacts are
local/internal diagnostic artifacts. External publishing requires a separate security and governance review.

The package lives under `ml-inference-service/offline_evaluation`. It has no network calls, database connectors,
Kafka clients, production service clients, scheduled jobs, endpoints, UI files, model artifact mutation, threshold
mutation, retraining module, or promotion workflow.

## Input Contract

The only supported active input is feedback dataset JSONL:

```text
{"type":"DATASET_METADATA", ...}
{"type":"DATASET_RECORD", "record": {...}}
```

The first non-empty line must be `DATASET_METADATA`. Metadata is required, dataset records must follow metadata,
unknown line types and fields are rejected, malformed JSONL is rejected, and multiple metadata lines are rejected.
Metadata population counters must reconcile with returned, excluded, skipped, and truncation-sentinel rows.

Failed feedback dataset builds abort evaluation. A metadata line with `failureReason != null` represents a failed
dataset build and must not be treated as an empty successful dataset.

Feedback dataset evaluation is not a permissive dual-format parser, and labels are not mixed across historical contracts.

The Feedback Dataset Evaluation label mapping is limited to:

- `POSITIVE_FRAUD` as the positive class.
- `NEGATIVE_LEGITIMATE` as the negative class.

The evaluator treats analyst feedback labels as bounded evaluation signals only. They are not ground truth, model-training
labels, final bank decisions, payment decisions, or automatic decisioning signals. Pseudonymous references are not
anonymization and remain internal parsing/report references only.

Feedback Dataset Evaluation fails fast on malformed or invalid schema input. Malformed-record exclusion counters are reserved for a
future tolerant evaluation mode and remain zero for successful reports. Invalid known fields, missing required
fields, unsafe values, inconsistent metadata, overlong lines, and inputs beyond the bounded feedback dataset limits abort
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

Feedback Dataset Evaluation treats `engineStatus` as the source of truth for operational availability. For non-AVAILABLE engine statuses,
risk and score bucket fields must be absent. `UNAVAILABLE`, `TIMEOUT`, `SKIPPED`, `DEGRADED`, and `FALLBACK_USED`
are not ranked and are not high/low signals.

Feedback dataset v2 supplies an exact bounded `mlPredictionScore`, `mlPredictionRiskLevel`, and
`mlPredictionExecutedAt` only when `mlPredictionEvidenceStatus = AVAILABLE`. `LEGITIMATELY_ABSENT` requires those
values and the model identity to be null and means only that direct evaluation evidence is legitimately absent.
`DIAGNOSTIC_EMISSION_DISABLED` does not prove that the primary or shadow ML runtime never executed. Identity validation
failure and source integrity failure are `MALFORMED`, while `IDENTITY_MISMATCH` is reserved for complete authoritative
identities or ownership tuples that demonstrably disagree. Platform recommendation diagnostics continue to use their
existing platform fields; model-specific evaluation must use only the direct ML evidence fields and must not
substitute platform score, risk, or recommendation values.

The evaluator accepts feedback dataset pseudonymous input references only for parsing and deterministic ordering. Reports are
aggregate-only and must not emit `evaluationRecordId`, `transactionReference`, `eval-`, or `txnref-` values.

Reason codes and diagnostic signals are validated as bounded machine-code values. These checks reject obvious unsafe
raw or sensitive patterns, but they are bounded safeguards, not a full DLP control.

## Reports

Reports are diagnostic aids only. They are not promotion criteria, not threshold-change criteria, and not production
approval criteria.

## Model-Specific Evaluation

The offline package also supports an optional aggregate-only ML model evaluation summary for an exact requested
`modelName`, `modelVersion`, and `featureContractVersion`. This is separate from Platform Recommendation Evaluation:
platform reports keep `subjectType = PLATFORM_RECOMMENDATION` and `modelIdentity = NOT_AVAILABLE`, while model-specific
reports use `subjectType = ML_MODEL` and exclude records with missing or mismatched lineage. Dataset v2 now carries
direct ML output evidence for the exact scoring occurrence; model-specific metric calculation is a separate bounded
step. The model evaluator classifies direct ML `HIGH`/`CRITICAL` risk as positive and `LOW`/`MEDIUM` as negative,
publishes a reconciled aggregate confusion matrix and rates, and never substitutes platform recommendation fields.
Missing or invalid prediction evidence and lineage mismatches remain explicit exclusion counts. Rates with zero
denominators are unavailable with bounded reasons instead of fake numeric values. Model ranking reuses the platform
ranking implementation but supplies exact `mlPredictionScore`, isolates the requested model identity, and orders ties
by pseudonymous evaluation record ID. Ranking does not claim score calibration. See
[ML Model Specific Evaluation](ml_model_specific_evaluation.md).

The model summary binds that evaluation to the exact source JSONL bytes through its canonical `sourceDataset` SHA-256
and reconciled source counters. Its manifest protects the summary and therefore that embedded identity; a trusted
reader can additionally verify it against supplied source bytes, while a standalone artifact read cannot establish
that external byte-level comparison. The reader reports `CLAIM_ONLY` when it validates only the artifact-protected
claim and `VERIFIED_AGAINST_SOURCE_BYTES` only after exact source bytes and the complete source identity match. These
runtime verification levels are not serialized into the summary and convey no ground-truth, approval, promotion,
calibration, payment, or production-primary authority.

Its aggregate Rules-vs-ML breakdown uses the Rules snapshot captured from the same scoring occurrence as the direct
ML evidence. Missing Rules evidence is counted as unavailable, never converted to low risk, and no current/latest
Engine Intelligence projection is consulted during dataset evaluation.

## Model Runtime Readiness

ML model artifacts expose `modelRuntimeReadiness` as a technical runtime contract. `READY` means the artifact is
structurally valid, aligned with the current feature contract, evaluated through the required temporal and
out-of-time lifecycle, and loadable by the strict ML runtime loader. It does not approve model promotion, production
primary decisioning, threshold governance, payment authorization, or automatic fraud decision authority. Governance
artifacts continue to represent production approval separately, including `productionApproval = NOT_APPROVED` where
applicable.

Generated reports are aggregate-first and include:

- input summary,
- rule-vs-ML disagreement summary,
- offline diagnostic quality metrics,
- exclusions,
- bounded warnings.

Platform Recommendation Evaluation Card v1 is implemented only by the current Feedback Dataset Evaluation pipeline
described below.

The report writer does not emit per-record output by default and does not emit raw transaction IDs, customer/account/
card/device/merchant identifiers, analyst IDs, submitted-by values, correlation IDs, idempotency keys, request hashes,
raw payloads, raw feature vectors, raw evidence, raw ML requests or responses, endpoints, tokens, secrets, stack
traces, exception messages, ground truth labels, model training labels, final decisions, payment authorization,
promotion signals, threshold recommendations, or analyst recommendations.

Retired budget/top-k metric aliases are not part of current Shadow Performance Summary v2, Evaluation Card v1,
or Promotion Review Readiness contracts. Current Platform Evaluation and Evaluation Card artifacts expose bounded aggregate metric objects and
metric availability instead of fake zeroes or legacy aliases.

Platform Evaluation reports are deterministic local artifacts for offline review. They include dataset summary, class balance,
alertRecommended confusion matrix, risk-level breakdown, fraud-score bucket analysis, precision@K, recall@K, and a
bounded disagreement report. Division by zero produces unavailable values rather than fake zeroes. Empty datasets,
single-class datasets, missing scores, missing alert recommendations, missing risk levels, truncation, and small sample
sizes are surfaced as warnings. Only feedback dataset `DATASET_RECORD` lines are metric rows. Low sample size warnings are not model-quality conclusions.

Platform Evaluation report sets use a manifest-last local artifact pattern. The writer prepares report payloads in memory, writes
temporary artifact files, writes `manifest.json.tmp`, replaces report artifacts, and replaces `manifest.json` last. Platform recommendation evaluation artifacts are written under `platform-evaluation/` with report type
`FEEDBACK_DATASET_OFFLINE_EVALUATION_V1` and artifact-set version
`feedback-dataset-evaluation-report-artifact-set-v1`. Optional model-specific evaluation artifacts are written as a separate `model-evaluation/` artifact set with report type
`ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1` and artifact-set version
`ml-model-feedback-dataset-evaluation-artifact-set-v1`; `model_evaluation_summary.json` is not part of the platform manifest. A report set is considered complete only when `manifest.json` exists in that report set, lists the expected artifact files, and each listed artifact matches
the manifest `sha256` and `sizeBytes`. The manifest is not external publishing, and scheduled generation or external
publication requires a separate security, governance, and observability review.

The `platform-evaluation/` and `model-evaluation/` families are independently complete under their own manifests.
No current production or offline consumer treats the parent directory as an atomically published combined artifact
set. Before a future Model Card or Promotion consumer relies on both families as one governed run, a run-level
completion manifest or equivalent last-written publication boundary is required.

An evaluation output directory represents exactly one evaluation run. A run may use a new directory or an existing
empty directory only. Any non-empty output directory is rejected before `platform-evaluation/` or `model-evaluation/`
is created; the runner does not merge runs, delete prior evidence, or infer ownership of existing artifacts. Downstream
consumers may rely on this one-directory, one-run ownership invariant, but must not infer combined completion of both
artifact families from the parent directory alone.

Platform Evaluation writers and readers use only `FEEDBACK_DATASET_OFFLINE_EVALUATION_V1` with
`feedback-dataset-evaluation-report-artifact-set-v1` and
`identityCompleteness = PLATFORM_RECOMMENDATION_NOT_MODEL_ARTIFACT_SCOPED`. Unsupported or mixed identities fail
closed.

Platform Evaluation report artifacts are not external exports and do not expose raw source identifiers, raw notes, raw payloads,
raw evidence, feature vectors, ground-truth fields, training labels, final decisions, payment authorization, model
promotion signals, or production threshold recommendations.

Platform Evaluation disagreement rows may include `decisionReasonCodes` because the feedback dataset validates them as bounded machine-code
values. They are allowed only in local/internal disagreement rows. They are not notes, not raw evidence, and must not
contain raw IDs, free text, payloads, tokens, or secrets.

Platform Recommendation Evaluation Card v1 is the single executable Platform Evaluation Card implementation. It is a local/internal governance
artifact generated from Platform Evaluation aggregate artifacts only. It accepts only canonical `manifest.json` and
`evaluation_summary.json` inputs from the Platform Evaluation artifact set, validates
`reportType = FEEDBACK_DATASET_OFFLINE_EVALUATION_V1` and
`artifactSetVersion = feedback-dataset-evaluation-report-artifact-set-v1`, bounds input size before full read, and preserves manifest
`sha256` and `sizeBytes` checks. Platform Evaluation owns `evaluationSubject`, `metricsSubject`, and `metricBasis`; Platform Recommendation Evaluation Card v1
copies those values and rejects caller-controlled model identity. Its `metricBasis` is
`ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK`, so `alertRecommendedConfusionMatrix` is a platform recommendation
diagnostic rather than direct model performance. It does not read `disagreement_report.jsonl` in v1, does not copy
the Platform Evaluation `disagreementSummary` into Platform Recommendation Evaluation Card v1, does not read the raw feedback dataset, and does not expose raw IDs
or per-record data. Its evidence counts use the feedback dataset `MAX_DATASET_RECORDS = 1000` limit. Its binary class-count invariant is
`positiveClassCount + negativeClassCount == recordsEvaluated`. Its published governance timestamps are real RFC3339
UTC `Z` date-times with optional 1-9 digit fractional seconds, and checked so the Platform Recommendation Evaluation Card `generatedAt` instant is not earlier than the
evaluation evidence instant. `sourceManifestSha256` is only a local lineage and integrity fingerprint, not a
signature, notarization, external attestation, immutability guarantee, or independent trust anchor. The source manifest
SHA-256 is not a signature, notarization, external attestation, immutability guarantee, or independent trust anchor. It is
not model promotion, not production approval, not threshold recommendation, not payment authorization, and not workflow or
case automation. Its `allowedUsageModes` values are documentation semantics only, not runtime permissions. The evaluation
card records `evaluationPurpose = OFFLINE_DIAGNOSTIC` and all runtime, promotion, threshold, payment, and workflow
authority fields as `NONE`.
