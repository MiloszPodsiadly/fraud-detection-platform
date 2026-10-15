# ML Model Card

Exact-artifact offline diagnostic evidence. This card grants no production decision or promotion authority.

## Model identity

- Model name: `python-logistic-fraud-model`
- Model version: `2026-09-19.rules-v2-canonical-ml-pln.suspicious-fact-ratio.v1`
- Feature contract: `2026-09-19.rules-v2-canonical-ml-pln.suspicious-fact-ratio`
- Artifact SHA-256: `55499538d93a634bb5c5fc2326380d013efaea8b0045320f838cad7405df15b6`
- Artifact verification: `VERIFIED_AGAINST_ARTIFACT_BYTES`

## Evaluation evidence

- Status: `AVAILABLE`
- Report type: `ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1`
- Report generated at: `2026-06-10T00:00:00Z`
- Report and manifest integrity: `VERIFIED_REPORT_AND_MANIFEST`
- Source dataset verification: `VERIFIED_AGAINST_SOURCE_BYTES`

### Source dataset

- Dataset version: `feedback-dataset-v3`
- SHA-256: `7fac7ded5995d96e2978f873ff5d9418e240c4fedeb24b551d8e346ad7e76b1b`
- Raw rows read: `0`
- Records returned: `0`
- Unresolved rows excluded: `0`
- Governance-review rows excluded: `0`
- Missing-required-field rows skipped: `0`
- Invalid source rows skipped: `0`
- Truncated: `false`

### Evaluation population

- recordsConsidered: `0`
- recordsEvaluated: `0`
- recordsExcludedIdentityMismatch: `0`
- recordsExcludedInvalidPredictionEvidence: `0`
- recordsExcludedMissingPredictionEvidence: `0`
- recordsExcludedSourceIdentityMismatch: `0`
- recordsExcludedUnexpectedMissingPredictionEvidence: `0`
- recordsWithPredictionEvidence: `0`

### Class balance

- Positive class count: `0`
- Negative class count: `0`

### Metric availability

- classBalance: available=`false`, reason=`INSUFFICIENT_MODEL_LINEAGE_RECORDS`
- mlPredictionMetrics: available=`false`, reason=`MODEL_PREDICTION_SIGNAL_UNAVAILABLE`
- rulesVsMlDisagreement: available=`false`, reason=`RULES_SIGNAL_UNAVAILABLE`

### Confusion matrix

- True positive: `0`
- False positive: `0`
- True negative: `0`
- False negative: `0`

### Prediction metrics

- Precision: available=`false`, value=`UNAVAILABLE`, reason=`NO_PREDICTED_POSITIVES`
- Recall: available=`false`, value=`UNAVAILABLE`, reason=`NO_ACTUAL_POSITIVES`
- True-positive rate: available=`false`, value=`UNAVAILABLE`, reason=`NO_ACTUAL_POSITIVES`
- False-positive rate: available=`false`, value=`UNAVAILABLE`, reason=`NO_ACTUAL_NEGATIVES`
- False-negative rate: available=`false`, value=`UNAVAILABLE`, reason=`NO_ACTUAL_POSITIVES`

### Precision at K

- K 1: requestedK=`1`, actualK=`0`, available=`false`, value=`UNAVAILABLE`, reason=`NO_SCORED_RECORDS`
- K 3: requestedK=`3`, actualK=`0`, available=`false`, value=`UNAVAILABLE`, reason=`NO_SCORED_RECORDS`

### Recall at K

- K 1: requestedK=`1`, actualK=`0`, available=`false`, value=`UNAVAILABLE`, reason=`NO_SCORED_RECORDS`
- K 3: requestedK=`3`, actualK=`0`, available=`false`, value=`UNAVAILABLE`, reason=`NO_SCORED_RECORDS`

### Rules-vs-ML disagreement

- Available: `false`
- Reason: `RULES_SIGNAL_UNAVAILABLE`
- Records with exact ML evidence: `0`
- Records compared: `0`
- Records excluded because Rules evidence was unavailable: `0`
- BOTH_HIGH: `0`
- BOTH_LOW: `0`
- ML_HIGH_RULES_LOW: `0`
- RULES_HIGH_ML_LOW: `0`

### Warnings

- `INSUFFICIENT_MODEL_LINEAGE_RECORDS`
- `MODEL_PREDICTION_SIGNAL_UNAVAILABLE`

### Limitations

- `ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS`
- `ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS`
- `MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION`
- `MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS`
- `MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING`

## Verified usage boundary

- Allowed modes: `COMPARE, OFFLINE_EVALUATION, SHADOW`
- Promotion state: `NOT_EVALUATED_FOR_PROMOTION`

### Intended use

- `EXACT_MODEL_OFFLINE_EVALUATION`
- `RULES_VS_ML_DIAGNOSTIC_COMPARISON`
- `SHADOW_MODEL_MONITORING`

### Not intended use

- `AUTOMATIC_APPROVE_DECLINE_OR_BLOCK`
- `MODEL_PROMOTION_APPROVAL`
- `PAYMENT_AUTHORIZATION`
- `PRIMARY_PRODUCTION_FRAUD_DECISIONING`
- `SCORING_THRESHOLD_CHANGE`
- `WORKFLOW_OR_ANALYST_RECOMMENDATION`

### Use limitations

- `ANALYST_FEEDBACK_IS_REVIEW_SIGNAL_NOT_GROUND_TRUTH`
- `BOUNDED_RETURNED_POPULATION_ONLY`
- `DOES_NOT_ESTABLISH_GLOBAL_DATASET_COMPLETENESS`
- `DOES_NOT_ESTABLISH_MODEL_CALIBRATION`
- `DOES_NOT_ESTABLISH_PRODUCTION_READINESS`
- `SOURCE_VERIFICATION_LEVEL_MUST_BE_INTERPRETED_EXPLICITLY`

- Runtime decision authority: `NONE`
- Promotion authority: `NONE`
- Threshold change authority: `NONE`
- Payment authorization authority: `NONE`
- Workflow authority: `NONE`
