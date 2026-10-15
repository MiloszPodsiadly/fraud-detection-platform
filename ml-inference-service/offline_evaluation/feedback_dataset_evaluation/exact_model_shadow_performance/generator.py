from __future__ import annotations

from typing import Any

from offline_evaluation.feedback_dataset_evaluation.dataset_schema import MAX_DATASET_RECORDS
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    EVALUATION_TIME_BASIS,
    MODEL_EVALUATION_METRIC_BASIS,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ValidatedModelEvaluationArtifactSet,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)
from offline_evaluation.feedback_dataset_evaluation.exact_model_shadow_performance.schema import (
    COHORT_ELIGIBILITY_POLICY,
    RETURNED_COHORT_COMPLETE,
    RETURNED_COHORT_NO_DATA,
    RETURNED_COHORT_PARTIAL,
    SOURCE_POPULATION_NOT_TRUNCATED,
    SOURCE_POPULATION_TRUNCATED,
    EXTERNAL_ATTESTATION,
    IMMUTABLE_STORAGE_GUARANTEE,
    POPULATION_TYPE,
    REPORT_TYPE,
    SCHEMA_VERSION,
    SOURCE_INTEGRITY_VERIFICATION,
    diagnostic_governance_boundary,
    unavailable_operational_diagnostics,
    validate_exact_model_shadow_performance,
)


def build_exact_model_shadow_performance(
        evidence: ValidatedModelEvaluationArtifactSet,
        generated_at: str,
) -> dict[str, Any]:
    evaluation = _thaw(evidence.summary)
    records_evaluated = evaluation["population"]["recordsEvaluated"]
    records_considered = evaluation["population"]["recordsConsidered"]
    if records_evaluated == 0:
        coverage_status = RETURNED_COHORT_NO_DATA
    elif records_evaluated < records_considered:
        coverage_status = RETURNED_COHORT_PARTIAL
    else:
        coverage_status = RETURNED_COHORT_COMPLETE
    summary = {
        "schemaVersion": SCHEMA_VERSION,
        "reportType": REPORT_TYPE,
        "generatedAt": generated_at,
        "populationType": POPULATION_TYPE,
        "cohortEligibility": {
            "policy": COHORT_ELIGIBILITY_POLICY,
            "eligibleLabels": ["CONFIRMED_FRAUD", "CONFIRMED_LEGITIMATE"],
            "requiresExactModelPredictionEvidence": True,
            "maximumRecords": MAX_DATASET_RECORDS,
            "metricBasis": MODEL_EVALUATION_METRIC_BASIS,
            "timeBasis": EVALUATION_TIME_BASIS,
        },
        "sourceEvaluation": {
            "reportType": MODEL_EVALUATION_REPORT_TYPE,
            "artifactSetVersion": MODEL_EVALUATION_ARTIFACT_SET_VERSION,
            "reportGeneratedAt": evaluation["generatedAt"],
            "manifestSha256": evidence.manifest_sha256,
            "artifactIntegrityVerification": SOURCE_INTEGRITY_VERIFICATION,
            "sourceDatasetVerificationLevel": evidence.verification_level.value,
            "externalAttestation": EXTERNAL_ATTESTATION,
            "immutableStorageGuarantee": IMMUTABLE_STORAGE_GUARANTEE,
        },
        "evaluationSummary": evaluation,
        "coverage": {
            "returnedCohortStatus": coverage_status,
            "sourcePopulationStatus": (
                SOURCE_POPULATION_TRUNCATED
                if evaluation["sourceDataset"]["truncated"]
                else SOURCE_POPULATION_NOT_TRUNCATED
            ),
        },
        "operationalDiagnostics": unavailable_operational_diagnostics(),
        "governance": diagnostic_governance_boundary(),
    }
    return validate_exact_model_shadow_performance(summary)


def _thaw(value: Any) -> Any:
    if isinstance(value, dict) or hasattr(value, "items"):
        return {key: _thaw(nested) for key, nested in value.items()}
    if isinstance(value, (tuple, list)):
        return [_thaw(item) for item in value]
    return value

