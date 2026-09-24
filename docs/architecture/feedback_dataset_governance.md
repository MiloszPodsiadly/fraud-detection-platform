# Feedback Dataset Governance

Status: FDP-122 governance foundation with feedback dataset internal bounded builder.

## Scope

FDP-122 defines internal eligibility rules for `fraud_feedback_records`. feedback dataset adds an internal bounded JSONL builder
that uses `FeedbackDatasetEligibilityPolicy` as the eligibility source of truth. feedback dataset does not add public dataset
export, CSV export, public dataset endpoint, ML evaluation, model training, model promotion, threshold recommendation,
payment decisioning, workflow automation, case creation, or Analyst Console UI.

The existing Engine Intelligence feedback dataset export is a separate bounded context from earlier work. feedback dataset is a
fraud feedback records builder only; it does not extend or replace the Engine Intelligence dataset export, does not read
`engine_intelligence_feedback`, and does not use Python `feedback_dataset.py` as source of truth.

## Eligibility

Feedback labels are analyst review outcomes, not certified legal ground truth.

Eligibility mapping:

- `CONFIRMED_FRAUD` -> `EVALUATION_CANDIDATE`
- `CONFIRMED_LEGITIMATE` -> `EVALUATION_CANDIDATE`
- `INCONCLUSIVE` -> `UNRESOLVED_EXCLUDED`
- `NEEDS_MORE_INFO` -> `UNRESOLVED_EXCLUDED`

`eligibleForBinaryEvaluation` is true only for `CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE`.

`eligibleForTrainingExport` is false for every label. feedback dataset output is for future evaluation only and is not training
data.

## Non-Claims

`CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE` are evaluation candidates only. They are not certified legal ground truth
and are not training labels in FDP-122.

`INCONCLUSIVE` and `NEEDS_MORE_INFO` are unresolved labels. They are excluded from binary evaluation.

Notes are analyst-readable context only. Notes are not training input and are not exported in feedback dataset. Any future notes
export would require separate governance and sanitization.

Reason codes are bounded signals, not raw evidence.

## Field Governance

FDP-122 provides a conservative internal dataset-field allowlist. feedback dataset defines the first internal bounded builder
schema from that governance foundation, but still does not create a public dataset export path.

Allowed governance fields are limited to bounded review and diagnostic fields such as `feedbackId`, `transactionId`,
`feedbackLabel`, `labelSource`, `feedbackStatus`, `createdAt`, `decisionReasonCodes`, `fraudScore`, `riskLevel`,
`alertRecommended`, `engineIntelligenceStatus`, Rules-vs-ML agreement/mismatch buckets, and bounded analyst
recommendation fields. FDP-139 also allows bounded ML diagnostic lineage snapshot fields:
`mlModelName`, `mlModelVersion`, and `mlFeatureContractVersion`. These fields describe the ML Engine Intelligence result
captured for the reviewed transaction; they are not model promotion approval and missing historical values remain
unknown.

Dangerous or future-decision fields are not eligible, including notes, raw notes, raw notes export, raw customer payload,
raw transaction payload, raw ML request/response, raw feature vector, raw evidence, ground truth, training label, final
decision, payment decision, payment authorization, token, secret, and password.

feedback dataset JSONL uses pseudonymous `evaluationRecordId` and `transactionReference` values instead of raw feedback or
transaction ids. These pseudonymous references are not anonymization and not a privacy boundary.

If the feedback dataset is ever exposed outside the internal service boundary, identifier strategy must be reviewed
separately, potentially using keyed HMAC or another approved pseudonymization mechanism. feedback dataset does not implement
that external export boundary.

The machine-readable JSONL envelope contract lives at `docs/schemas/feedback_dataset_record.schema.json`. The metadata
line is not an evaluation row; future consumers must build evaluation rows from `DATASET_RECORD` lines only.
