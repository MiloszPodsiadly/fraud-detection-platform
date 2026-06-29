# Feedback Dataset Builder

Status: FDP-123 internal bounded builder.

## Scope

FDP-123 adds an internal bounded builder for `fraud_feedback_records`. It creates deterministic JSONL for future
ML/rules evaluation work. The builder is an internal service and writer only.

FDP-123 does not add a public dataset API, controller, OpenAPI path, UI, scheduler, CLI export, automatic runtime file
export, Kafka publication, ML evaluation, model training, model promotion, threshold recommendation, payment
authorization, approve/decline/block behavior, workflow automation, or case creation.

The output is an evaluation artifact only. It is not training data, not certified legal ground truth, not a final bank
decision, and not a payment decision. Python ML evaluation remains future FDP-124.

## Bounded Context

The source of truth is:

- `FraudFeedbackRecord`
- `FeedbackDatasetEligibilityPolicy`

The builder reads `fraud_feedback_records` only. It does not read `engine_intelligence_feedback`.

This is separate from the Engine Intelligence Feedback Dataset Export bounded context. FDP-123 does not replace FDP-102,
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

## Eligibility And Labels

The builder does not duplicate eligibility rules. It calls `FeedbackDatasetEligibilityPolicy` first:

- `CONFIRMED_FRAUD` -> `EVALUATION_CANDIDATE` -> `POSITIVE_FRAUD`
- `CONFIRMED_LEGITIMATE` -> `EVALUATION_CANDIDATE` -> `NEGATIVE_LEGITIMATE`
- `INCONCLUSIVE` -> excluded
- `NEEDS_MORE_INFO` -> excluded
- null or governance-review labels -> excluded

Unresolved labels are not written to JSONL v1.

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
`fraudScore`, `riskLevel`, `alertRecommended`, Engine Intelligence status/agreement/mismatch/score-delta buckets,
Analyst Recommendation status/value/version/generated-at/reason codes, `scoredAt`, and `transactionTimestamp`.

The builder never serializes `FraudFeedbackRecord` directly.

## Identifier Safety

JSONL does not include raw `feedbackId` or raw `transactionId`. It uses deterministic SHA-256 based references:

- `evaluationRecordId`
- `transactionReference`

These are pseudonymous internal references. They are not anonymization and not a privacy boundary.

The output does not include customer id, correlation id, created-by actor, notes, raw notes, raw payloads, raw evidence,
raw ML requests/responses, feature vectors, legal/final/payment decision fields, or secrets.

## Result And Failure Semantics

`FeedbackDatasetBuildResult` distinguishes:

- successful empty build: store read succeeded and no eligible records were returned
- failed build: `failureReason` is explicit and no dataset record lines are emitted
- truncated build: `truncated=true`, `rawRowsRead > maxRecords`, records are capped

Store failure returns `FEEDBACK_STORE_UNAVAILABLE`. Invalid request returns `INVALID_REQUEST`. Missing required source
fields are counted in `skippedMissingRequiredFieldCount` and do not create fake records.

## JSONL

The writer emits deterministic JSONL:

1. one `DATASET_METADATA` line
2. zero or more `DATASET_RECORD` lines

Failed builds emit metadata with a bounded `failureReason` and no fake successful record lines.

