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
- SHA-256: `fe27dc33bec10571517d96189dcba31e6a210be03b7306390b2f447fe6b7814a`
- Raw rows read: `7`
- Records returned: `7`
- Unresolved rows excluded: `0`
- Governance-review rows excluded: `0`
- Missing-required-field rows skipped: `0`
- Invalid source rows skipped: `0`
- Truncated: `false`

### Evaluation population

- recordsConsidered: `7`
- recordsEvaluated: `7`
- recordsExcludedIdentityMismatch: `0`
- recordsExcludedInvalidPredictionEvidence: `0`
- recordsExcludedMissingPredictionEvidence: `0`
- recordsExcludedSourceIdentityMismatch: `0`
- recordsExcludedUnexpectedMissingPredictionEvidence: `0`
- recordsWithPredictionEvidence: `7`

### Class balance

- Positive class count: `4`
- Negative class count: `3`

### Metric availability

- classBalance: available=`true`, reason=`NONE`
- mlPredictionMetrics: available=`true`, reason=`NONE`
- rulesVsMlDisagreement: available=`true`, reason=`NONE`

### Confusion matrix

- True positive: `2`
- False positive: `1`
- True negative: `2`
- False negative: `2`

### Prediction metrics

- Precision: available=`true`, value=`0.666667`, reason=`NONE`
- Recall: available=`true`, value=`0.5`, reason=`NONE`
- True-positive rate: available=`true`, value=`0.5`, reason=`NONE`
- False-positive rate: available=`true`, value=`0.333333`, reason=`NONE`
- False-negative rate: available=`true`, value=`0.5`, reason=`NONE`

### Precision at K

- K 1: requestedK=`1`, actualK=`1`, available=`true`, value=`1.0`, reason=`NONE`
- K 3: requestedK=`3`, actualK=`3`, available=`true`, value=`0.666667`, reason=`NONE`
- K 5: requestedK=`5`, actualK=`5`, available=`true`, value=`0.6`, reason=`NONE`
- K 7: requestedK=`7`, actualK=`7`, available=`true`, value=`0.571429`, reason=`NONE`

### Recall at K

- K 1: requestedK=`1`, actualK=`1`, available=`true`, value=`0.25`, reason=`NONE`
- K 3: requestedK=`3`, actualK=`3`, available=`true`, value=`0.5`, reason=`NONE`
- K 5: requestedK=`5`, actualK=`5`, available=`true`, value=`0.75`, reason=`NONE`
- K 7: requestedK=`7`, actualK=`7`, available=`true`, value=`1.0`, reason=`NONE`

### Rules-vs-ML disagreement

- Available: `true`
- Reason: `NONE`
- Records with exact ML evidence: `7`
- Records compared: `7`
- Records excluded because Rules evidence was unavailable: `0`
- BOTH_HIGH: `1`
- BOTH_LOW: `2`
- ML_HIGH_RULES_LOW: `2`
- RULES_HIGH_ML_LOW: `2`

### Warnings

- `NONE`

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
