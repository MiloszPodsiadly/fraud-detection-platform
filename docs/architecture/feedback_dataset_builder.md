# Feedback Dataset Builder

Status: feedback dataset internal bounded builder.

## Scope

feedback dataset adds an internal bounded builder for `fraud_feedback_records`. It creates envelope JSONL for future ML/rules
evaluation work. The builder is an internal service and writer only.

feedback dataset does not add a public dataset API, controller, OpenAPI path, UI, scheduler, CLI export, automatic runtime file
export, Kafka publication, ML evaluation, model training, model promotion, threshold recommendation, payment
authorization, approve/decline/block behavior, workflow automation, or case creation.

The output is an evaluation artifact only. It is not training data, not certified legal ground truth, not a final bank
decision, and not a payment decision. Python ML evaluation is a separate offline consumer.

## Bounded Context

The source of truth is:

- `FraudFeedbackRecord`
- `FeedbackDatasetEligibilityPolicy`

The builder reads `fraud_feedback_records` only. It does not read `engine_intelligence_feedback`.

This is separate from the Engine Intelligence Feedback Dataset Export bounded context. The feedback dataset does not replace that export contract,
does not use `alert-service/src/main/java/com/frauddetection/alert/engineintelligence/dataset` as source of truth, and
does not use `ml-inference-service/app/feedback/feedback_dataset.py` as source of truth.

## Request And Query

`FeedbackDatasetBuildRequest` uses `FEEDBACK_CREATED_AT` as the only time basis. Filtering is:

`fromInclusive <= createdAt <= toInclusive`

This is feedback creation time, not transaction time and not `scoredAt`.

The bounded query reads candidate feedback rows ordered by:

1. `createdAt ASC`
2. `feedbackId ASC`

The query limits reads to `maxRecords + 1` to detect truncation. The default max is 500, hard max is 1000, and the date
range cap is 31 days.

`rawRowsRead` is the bounded number of candidate rows fetched from storage for this build. It is not the total number
of matching records in the database. When `truncated=true`, more matching records may exist beyond the bounded read
window.

Successful metadata reconciles every bounded row as returned, excluded, or skipped. A truncated read contains exactly
one additional sentinel row fetched only to prove that more candidates exist.

## Eligibility And Labels

The builder does not duplicate eligibility rules. It calls `FeedbackDatasetEligibilityPolicy` first:

- `CONFIRMED_FRAUD` -> `EVALUATION_CANDIDATE` -> `POSITIVE_FRAUD`
- `CONFIRMED_LEGITIMATE` -> `EVALUATION_CANDIDATE` -> `NEGATIVE_LEGITIMATE`
- `INCONCLUSIVE` -> excluded
- `NEEDS_MORE_INFO` -> excluded
- null or governance-review labels -> excluded

Unresolved labels are not written to JSONL v2.

`FeedbackDatasetRecord` also enforces the record-level invariant. Only these pairs can be represented:

- `CONFIRMED_FRAUD` + `POSITIVE_FRAUD`
- `CONFIRMED_LEGITIMATE` + `NEGATIVE_LEGITIMATE`

The dataset boundary validates `decisionReasonCodes` before serialization. Codes must be non-empty, known
`FraudFeedbackReasonCode` values, compatible with the feedback label using the fraud feedback write-path rules, and
bounded to at most 10 values. Unknown, unsafe, or label-incompatible reason codes cause the source row to be skipped;
no fake dataset record is emitted.

## Record Shape

Required fields:

- `datasetVersion`
- `evaluationRecordId`
- `transactionReference`
- `feedbackLabel`
- `evaluationLabel`
- `decisionReasonCodes`
- `feedbackCreatedAt`

Optional nullable fields are limited to bounded feedback diagnostics already present on `FraudFeedbackRecord`:
`fraudScore`, `riskLevel`, `alertRecommended`, Engine Intelligence status and Rules-vs-ML
agreement/mismatch/score-delta buckets, Analyst Recommendation status/value/version/generated-at/reason codes,
`scoredAt`, and `transactionTimestamp`.

The v2 record shape carries bounded direct ML evidence through `mlPredictionEvidenceStatus`, `mlPredictionScore`,
`mlPredictionRiskLevel`, and `mlPredictionExecutedAt`, together with `mlModelName`, `mlModelVersion`, and
`mlFeatureContractVersion`. `AVAILABLE` requires the complete signal and model identity. `LEGITIMATELY_ABSENT`
requires every direct prediction and model identity field to be null; absence is never represented as score zero or
low risk.

The same record carries only the bounded Rules-side snapshot needed for comparison: `rulesEvidenceStatus` and
`rulesRiskLevel`. These values are captured on `FraudFeedbackRecord` from the occurrence-validated Engine Intelligence
read during feedback creation. Dataset construction never looks up a current Rules projection. `UNAVAILABLE` requires
a null risk level and is not interpreted as `LOW`, so a later rescore cannot replace the historical Rules signal.

The builder resolves this evidence only through the feedback record's exact authoritative `sourceEventId` and the
immutable `MlPredictionEvidenceProjection` keyed by that event. It validates occurrence timestamp, transaction
ownership, optional correlation ownership, and any captured feedback model identity. Contradictions fail closed and
are counted as invalid source rows. The lookup is one bounded `findAllById` batch after the dataset row limit is
applied; there is no transaction-to-latest, current projection, registry, runtime-model, or timestamp-proximity
fallback.

The builder never serializes `FraudFeedbackRecord` directly.

## Identifier Safety

JSONL does not include raw `feedbackId` or raw `transactionId`. It uses deterministic SHA-256 based references:

- `evaluationRecordId`
- `transactionReference`

These are pseudonymous internal references. They are not anonymization and not a privacy boundary.

If this dataset is ever exposed outside the internal service boundary, identifier strategy must be reviewed separately,
potentially using keyed HMAC or another approved pseudonymization mechanism.

The output does not include customer id, correlation id, created-by actor, notes, raw notes, raw payloads, raw evidence,
raw ML requests/responses, feature vectors, legal/final/payment decision fields, or secrets.

The dataset does not export the private scoring occurrence identity used for the internal join. It exports only the
bounded direct prediction fields required by offline evaluation. Consumers must never infer missing evidence or
lineage from the platform score, current runtime, registry, model version, latest projection, or timestamps.

## Result And Failure Semantics

`FeedbackDatasetBuildResult` distinguishes:

- successful empty build: store read succeeded and no eligible records were returned
- failed build: `failureReason` is explicit and no dataset record lines are emitted
- truncated build: `truncated=true`, `rawRowsRead > maxRecords`, records are capped

Store failure returns `FEEDBACK_STORE_UNAVAILABLE`. Invalid request returns `INVALID_REQUEST`. Missing required source
fields are counted in `skippedMissingRequiredFieldCount` and do not create fake records. Corrupted source rows with
unknown, unsafe, or label-incompatible reason codes, invalid source identifiers, or unsafe optional values are counted
in `skippedInvalidSourceRecordCount`.

## JSONL

The writer emits envelope JSONL:

1. one `DATASET_METADATA` line
2. zero or more `DATASET_RECORD` lines

Consumers must ignore or separately parse lines where `type != DATASET_RECORD` when constructing evaluation rows.
`DATASET_METADATA` is not an evaluation row. Consumers may read it for counts, request time range, truncation, and
failure reason.

Dataset record lines are deterministic for the same source rows and request. The metadata line includes `builtAt`, so
full JSONL bytes can differ across runtime builds unless `Clock` is fixed in tests.

Failed builds emit metadata with a bounded `failureReason` and no fake successful record lines.

## Schema

`docs/schemas/feedback_dataset_record.schema.json` is the machine-readable JSONL envelope contract for current Python evaluation
consumers. It covers both `DATASET_METADATA` and `DATASET_RECORD` line shapes. It does not add a public API or runtime
export path in feedback dataset.
