# Feedback Dataset Governance

Status: FDP-122 governance foundation only.

## Scope

FDP-122 defines internal eligibility rules for `fraud_feedback_records`. It does not add dataset export, dataset
builder, CSV export, JSON export, public dataset endpoint, ML evaluation, model training, model promotion, threshold
recommendation, payment decisioning, workflow automation, case creation, or Analyst Console UI.

The existing Engine Intelligence feedback dataset export is a separate bounded context from earlier work. FDP-122 does
not extend it and does not export fraud feedback records.

## Eligibility

Feedback labels are analyst review outcomes, not certified legal ground truth.

Eligibility mapping:

- `CONFIRMED_FRAUD` -> `EVALUATION_CANDIDATE`
- `CONFIRMED_LEGITIMATE` -> `EVALUATION_CANDIDATE`
- `INCONCLUSIVE` -> `UNRESOLVED_EXCLUDED`
- `NEEDS_MORE_INFO` -> `UNRESOLVED_EXCLUDED`

`eligibleForBinaryEvaluation` is true only for `CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE`.

`eligibleForTrainingExport` is false for every label in FDP-122.

## Non-Claims

`CONFIRMED_FRAUD` and `CONFIRMED_LEGITIMATE` are evaluation candidates only. They are not certified legal ground truth
and are not training labels in FDP-122.

`INCONCLUSIVE` and `NEEDS_MORE_INFO` are unresolved labels. They are excluded from binary evaluation.

Notes are analyst-readable context only. Notes are not training input and are not exported in FDP-122. Any future notes
export would require separate governance and sanitization.

Reason codes are bounded signals, not raw evidence.
