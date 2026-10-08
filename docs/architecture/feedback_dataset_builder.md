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

The bounded context has two data sources with distinct ownership:

- `FraudFeedbackRecord` owns analyst feedback, exact scoring-occurrence ownership, and the bounded historical
  snapshots captured when feedback is created.
- `MlPredictionEvidenceProjection` owns direct ML prediction evidence for the exact source event.
- `FeedbackDatasetEligibilityPolicy` owns label eligibility.

The builder reads bounded candidates from `fraud_feedback_records` and performs one bounded exact-event lookup in
`ml_prediction_evidence_projections`. It does not read `engine_intelligence_feedback`, a current transaction
projection, or the model registry.

`feedback-dataset-v3` contains only current exact-occurrence evaluation observations. A candidate must carry complete,
valid scoring-occurrence ownership before it enters the evidence batch lookup. A fully missing occurrence is counted
in `skippedMissingRequiredFieldCount`; partial or malformed occurrence identity is counted in
`skippedInvalidSourceRecordCount`. Neither condition emits a `DATASET_RECORD`.

Pre-lineage fraud feedback remains governed historical audit data, not current evaluation input. It must be inventoried
and archived or quarantined under the retention policy; archive does not mean delete, and dataset construction never
rewrites historical lineage. Source ingestion accounting keeps every excluded source row visible without exporting
private occurrence identifiers.

This is separate from the Engine Intelligence analyst usefulness/accuracy feedback bounded context owned by
`EngineIntelligenceFeedbackDataset*`. The feedback dataset does not replace that export contract, does not use
`EngineIntelligenceFeedbackDatasetExport`, a current Engine Intelligence projection selected by transaction ID, or
a latest/current diagnostic projection as the source of exact model prediction evidence. It does not use
`alert-service/src/main/java/com/frauddetection/alert/engineintelligence/dataset` as source of truth and does not use
the removed competing `app.feedback.feedback_dataset` local training-store path as source of truth.

This exact-evidence evaluation scope does not implement a Model Card, Promotion Workflow, retraining, model
activation, threshold automation, Device Risk, Merchant Risk, Graph Risk, or recommendation automation.

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

The v3 record shape carries bounded direct ML evidence through `mlPredictionEvidenceStatus`,
`mlPredictionEvidenceOmissionReason`, `mlPredictionScore`, `mlPredictionRiskLevel`, and
`mlPredictionExecutedAt`, together with `mlModelName`, `mlModelVersion`, `mlFeatureContractVersion`, and
`mlModelArtifactSha256`. `AVAILABLE` requires the complete signal and exact M/V/F/SHA identity. Every non-available status requires the direct
prediction and model identity fields to be null. `LEGITIMATELY_ABSENT` additionally requires an authoritative
omission reason; missing projection or identity data alone never proves legitimate absence. Unexpected absence,
malformed evidence, and identity mismatch remain explicit dataset records rather than disappearing from the bounded
population. Absence is never represented as score zero or low risk.

The repository currently has no attested historical partition/offset boundary that can independently prove legitimate
absence. Consequently, historical age, missing fields, or timestamps never produce `LEGITIMATELY_ABSENT`; only the
authoritative omission mapping below can do so until the external cutover evidence is completed.

The authoritative omission mapping is:

| Scored-event omission reason | Dataset status | Meaning |
| --- | --- | --- |
| `DIAGNOSTIC_EMISSION_DISABLED` | `LEGITIMATELY_ABSENT` | Direct evaluation evidence was intentionally not emitted; this does not assert that ML inference never executed. |
| `DIAGNOSTIC_ENRICHMENT_UNAVAILABLE` | `MISSING_UNEXPECTEDLY` | Diagnostic enrichment failed before a direct ML engine outcome could be established. |
| `ML_ENGINE_UNAVAILABLE` | `MISSING_UNEXPECTEDLY` | The expected ML engine did not provide usable evidence. |
| `SOURCE_TIMESTAMP_MISSING` | `MALFORMED` | Required source execution time was absent. |
| `INVALID_SCORE` | `MALFORMED` | The direct score was absent or outside its contract. |
| `IDENTITY_VALIDATION_FAILURE` | `MALFORMED` | Model identity could not be validated; this is not an exact mismatch. |
| `EVIDENCE_SOURCE_INTEGRITY_FAILURE` | `MALFORMED` | The expected ML source result was missing or used the wrong engine type. |
| `PREDICTION_NOT_ACCEPTED` | `MALFORMED` | The prediction did not survive the bounded acceptance contract. |

`IDENTITY_MISMATCH` is reserved for two complete, structurally valid authoritative identities or ownership tuples
that demonstrably disagree. It is never inferred from missing model metadata. Model-evaluation
`recordsWithPredictionEvidence` counts only usable direct prediction evidence; upstream source identity conflicts are
reported separately and do not increment that count.

The same record carries only the bounded Rules-side snapshot needed for comparison: `rulesEvidenceStatus` and
`rulesRiskLevel`. These values are captured on `FraudFeedbackRecord` from the occurrence-validated Engine Intelligence
read during feedback creation. Dataset construction never looks up a current Rules projection. `UNAVAILABLE` requires
a null risk level and is not interpreted as `LOW`, so a later rescore cannot replace the historical Rules signal.

The builder resolves this evidence only through the feedback record's exact authoritative `sourceEventId` and the
immutable `MlPredictionEvidenceProjection` keyed by that event. It validates occurrence timestamp, transaction
ownership, optional correlation ownership, and any captured feedback model identity. The scoring occurrence carries
either exact prediction evidence or a bounded authoritative omission reason through the scored event and feedback
snapshot. Scored events with neither field are outside the current contract and fail closed; legacy messages must be
drained, migrated from authoritative evidence, archived, or quarantined before current consumption. The missing
outcome never becomes legitimate absence. Evidence
resolution contradictions are retained with `MALFORMED` or `IDENTITY_MISMATCH`; malformed
non-ML source contracts still fail closed as invalid source rows. The lookup is one bounded `findAllById` batch after
the dataset row limit is applied; there is no transaction-to-latest, current projection, registry, runtime-model, or
timestamp-proximity fallback.

A valid analyst feedback row with missing, unavailable, malformed, or mismatched ML evidence remains a successful,
explicit dataset observation. It is excluded only from the exact model metric population according to its bounded
status and remains represented in model population counters. No valid feedback observation silently disappears from
population accounting solely because ML evidence is unusable.

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

Candidate feedback store failure returns `FEEDBACK_STORE_UNAVAILABLE`. A transient evidence repository read failure
returns `ML_PREDICTION_EVIDENCE_STORE_UNAVAILABLE`; an impossible repository result or evidence-resolution invariant
failure returns `ML_PREDICTION_EVIDENCE_INTEGRITY_FAILURE`. These whole-build failures emit no records and never expose
exception text. A row-level `MISSING_UNEXPECTEDLY`, `MALFORMED`, or `IDENTITY_MISMATCH` outcome remains a successful,
explicit dataset observation only after exact-occurrence eligibility is established. Invalid request returns
`INVALID_REQUEST`. Missing required source fields, including fully absent occurrence lineage, are counted in
`skippedMissingRequiredFieldCount` and do not create fake records. Corrupted source rows with partial or malformed
occurrence lineage, unknown, unsafe, or label-incompatible reason codes, invalid source identifiers, or unsafe optional
values are counted in `skippedInvalidSourceRecordCount`.

Build outcomes and successful ML evidence statuses are recorded through the service's Micrometer registry using only
bounded `result` and `status` labels. Raw identifiers, model versions, payloads, and exception details are not metric
labels or log fields.

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

## V2 Rollout

Current validators and runtime artifacts intentionally require `feedback-dataset-v3`; there is no executable v1 or v2
fallback. Deployment order is therefore contractual: generate or publish valid v2 evaluation artifacts, verify those
artifacts against the v2 validators, and only then deploy the runtime that requires v2. A missing or invalid v2
artifact must fail closed instead of silently removing Shadow Performance diagnostics or loading a v1 artifact.
