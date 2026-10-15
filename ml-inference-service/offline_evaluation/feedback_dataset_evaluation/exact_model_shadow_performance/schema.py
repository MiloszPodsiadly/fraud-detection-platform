from __future__ import annotations

import copy
import hashlib
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.dataset_schema import MAX_DATASET_RECORDS
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    EVALUATION_TIME_BASIS,
    MODEL_EVALUATION_METRIC_BASIS,
    validate_model_evaluation_subject,
    validate_model_evaluation_summary,
    validate_model_evaluation_window,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ModelEvaluationArtifactVerificationLevel,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import (
    compare_rfc3339_timestamps,
    normalize_rfc3339_timestamp,
)
from offline_evaluation.json_contract import dumps_strict_json


class ExactModelShadowPerformanceValidationError(ValueError):
    """Raised when exact-artifact shadow evidence violates the canonical contract."""


SCHEMA_VERSION = "exact-model-shadow-performance-v1"
REPORT_TYPE = "EXACT_MODEL_SHADOW_PERFORMANCE_V1"
POPULATION_TYPE = "LABELED_FEEDBACK"
COHORT_ELIGIBILITY_POLICY = "BINARY_ANALYST_FEEDBACK_WITH_EXACT_ML_PREDICTION"
SOURCE_INTEGRITY_VERIFICATION = "VERIFIED_REPORT_AND_MANIFEST"
EXTERNAL_ATTESTATION = "NOT_PROVIDED"
IMMUTABLE_STORAGE_GUARANTEE = "NOT_CLAIMED"
MAX_SUMMARY_BYTES = 524_288

COVERAGE_COMPLETE = "COMPLETE"
COVERAGE_NO_DATA = "NO_DATA"
COVERAGE_PARTIAL = "PARTIAL_COVERAGE"

LATENCY_UNAVAILABLE = "EXACT_ARTIFACT_LATENCY_EVIDENCE_UNAVAILABLE"
FALLBACK_UNAVAILABLE = "EXACT_ARTIFACT_FALLBACK_EVIDENCE_UNAVAILABLE"
REASON_CODES_UNAVAILABLE = "EXACT_ARTIFACT_REASON_CODE_EVIDENCE_UNAVAILABLE"

ROOT_FIELDS = {
    "schemaVersion",
    "reportType",
    "generatedAt",
    "populationType",
    "cohortEligibility",
    "sourceEvaluation",
    "evaluationSummary",
    "coverage",
    "operationalDiagnostics",
    "governance",
}
COHORT_FIELDS = {
    "policy",
    "eligibleLabels",
    "requiresExactMlPredictionEvidence",
    "maximumRecords",
    "metricBasis",
    "timeBasis",
}
SOURCE_EVALUATION_FIELDS = {
    "reportType",
    "artifactSetVersion",
    "reportGeneratedAt",
    "manifestSha256",
    "artifactIntegrityVerification",
    "sourceDatasetVerificationLevel",
    "externalAttestation",
    "immutableStorageGuarantee",
}
COVERAGE_FIELDS = {"status", "sourceTruncated", "sourceInvalid"}
OPERATIONAL_FIELDS = {"mlLatencyP95", "fallbackRate", "mlReasonCodeDistribution"}
GOVERNANCE_FIELDS = {
    "diagnosticOnly",
    "runtimeDecisionAuthority",
    "promotionAuthority",
    "thresholdChangeAuthority",
    "paymentAuthorizationAuthority",
    "workflowAuthority",
}


def validate_exact_model_shadow_performance(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ExactModelShadowPerformanceValidationError("exact-model shadow summary must be an object")
    try:
        encoded = dumps_strict_json(raw, sort_keys=True, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise ExactModelShadowPerformanceValidationError("summary must contain strict finite JSON") from exc
    if len(encoded) > MAX_SUMMARY_BYTES:
        raise ExactModelShadowPerformanceValidationError("summary exceeds maximum payload size")
    _exact_fields(raw, ROOT_FIELDS, "summary")
    summary = copy.deepcopy(raw)
    _constant(summary, "schemaVersion", SCHEMA_VERSION)
    _constant(summary, "reportType", REPORT_TYPE)
    generated_at = _timestamp(summary.get("generatedAt"), "generatedAt")
    _constant(summary, "populationType", POPULATION_TYPE)
    _validate_cohort(summary.get("cohortEligibility"))
    source_evaluation = _validate_source_evaluation(summary.get("sourceEvaluation"))
    evaluation_summary = _validate_evaluation_summary(summary.get("evaluationSummary"))
    if source_evaluation["reportGeneratedAt"] != evaluation_summary["generatedAt"]:
        raise ExactModelShadowPerformanceValidationError(
            "source evaluation timestamp must match the embedded evaluation summary"
        )
    if compare_rfc3339_timestamps(generated_at, evaluation_summary["generatedAt"]) < 0:
        raise ExactModelShadowPerformanceValidationError("generatedAt must not precede source evaluation")
    _validate_coverage(summary.get("coverage"), evaluation_summary)
    _validate_operational_diagnostics(summary.get("operationalDiagnostics"))
    _validate_governance(summary.get("governance"))
    return summary


def exact_report_selection_key(subject: dict[str, Any], window: dict[str, Any]) -> str:
    validated_subject = _validated_subject(subject)
    validated_window = _validated_window(window)
    fields = (
        validated_subject["modelName"],
        validated_subject["modelVersion"],
        validated_subject["featureContractVersion"],
        validated_subject["modelArtifactSha256"],
        validated_window.get("fromInclusive") or "",
        validated_window.get("toInclusive") or "",
    )
    payload = "exact-model-shadow-selection-v1\n" + "\n".join(fields)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _validate_cohort(value: Any) -> None:
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError("cohortEligibility must be an object")
    _exact_fields(value, COHORT_FIELDS, "cohortEligibility")
    expected = {
        "policy": COHORT_ELIGIBILITY_POLICY,
        "eligibleLabels": ["CONFIRMED_FRAUD", "CONFIRMED_LEGITIMATE"],
        "requiresExactMlPredictionEvidence": True,
        "maximumRecords": MAX_DATASET_RECORDS,
        "metricBasis": MODEL_EVALUATION_METRIC_BASIS,
        "timeBasis": EVALUATION_TIME_BASIS,
    }
    if value != expected:
        raise ExactModelShadowPerformanceValidationError("cohortEligibility is unsupported")


def _validate_source_evaluation(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError("sourceEvaluation must be an object")
    _exact_fields(value, SOURCE_EVALUATION_FIELDS, "sourceEvaluation")
    _constant(value, "reportType", MODEL_EVALUATION_REPORT_TYPE)
    _constant(value, "artifactSetVersion", MODEL_EVALUATION_ARTIFACT_SET_VERSION)
    _timestamp(value.get("reportGeneratedAt"), "sourceEvaluation.reportGeneratedAt")
    _sha256(value.get("manifestSha256"), "sourceEvaluation.manifestSha256")
    _constant(value, "artifactIntegrityVerification", SOURCE_INTEGRITY_VERIFICATION)
    levels = {level.value for level in ModelEvaluationArtifactVerificationLevel}
    if value.get("sourceDatasetVerificationLevel") not in levels:
        raise ExactModelShadowPerformanceValidationError(
            "sourceEvaluation.sourceDatasetVerificationLevel is unsupported"
        )
    _constant(value, "externalAttestation", EXTERNAL_ATTESTATION)
    _constant(value, "immutableStorageGuarantee", IMMUTABLE_STORAGE_GUARANTEE)
    return value


def _validate_evaluation_summary(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError("evaluationSummary must be an object")
    try:
        validated = validate_model_evaluation_summary(value)
    except ValueError as exc:
        raise ExactModelShadowPerformanceValidationError(f"evaluationSummary invalid: {exc}") from exc
    _validated_subject(validated["evaluationSubject"])
    _validated_window(validated["evaluationWindow"])
    return validated


def _validated_subject(value: Any) -> dict[str, Any]:
    try:
        return validate_model_evaluation_subject(value)
    except ValueError as exc:
        raise ExactModelShadowPerformanceValidationError(str(exc)) from exc


def _validated_window(value: Any) -> dict[str, Any]:
    try:
        return validate_model_evaluation_window(value)
    except ValueError as exc:
        raise ExactModelShadowPerformanceValidationError(str(exc)) from exc


def _validate_coverage(value: Any, evaluation: dict[str, Any]) -> None:
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError("coverage must be an object")
    _exact_fields(value, COVERAGE_FIELDS, "coverage")
    population = evaluation["population"]
    if population["recordsEvaluated"] == 0:
        status = COVERAGE_NO_DATA
    elif population["recordsEvaluated"] < population["recordsConsidered"]:
        status = COVERAGE_PARTIAL
    else:
        status = COVERAGE_COMPLETE
    expected = {
        "status": status,
        "sourceTruncated": evaluation["sourceDataset"]["truncated"],
        "sourceInvalid": False,
    }
    if value != expected:
        raise ExactModelShadowPerformanceValidationError("coverage is inconsistent with evaluation population")


def _validate_operational_diagnostics(value: Any) -> None:
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError("operationalDiagnostics must be an object")
    _exact_fields(value, OPERATIONAL_FIELDS, "operationalDiagnostics")
    expected = unavailable_operational_diagnostics()
    if value != expected:
        raise ExactModelShadowPerformanceValidationError(
            "operational diagnostics require exact-artifact source evidence"
        )


def unavailable_operational_diagnostics() -> dict[str, Any]:
    return {
        "mlLatencyP95": {
            "available": False, "valueMilliseconds": None, "sampleSize": None,
            "timeBasis": None, "reason": LATENCY_UNAVAILABLE,
        },
        "fallbackRate": {
            "available": False, "value": None, "numerator": None, "denominator": None,
            "timeBasis": None, "reason": FALLBACK_UNAVAILABLE,
        },
        "mlReasonCodeDistribution": {
            "available": False, "counts": {}, "recordsEvaluated": None,
            "timeBasis": None, "reason": REASON_CODES_UNAVAILABLE,
        },
    }


def _validate_governance(value: Any) -> None:
    if not isinstance(value, dict):
        raise ExactModelShadowPerformanceValidationError("governance must be an object")
    _exact_fields(value, GOVERNANCE_FIELDS, "governance")
    expected = diagnostic_governance_boundary()
    if value != expected:
        raise ExactModelShadowPerformanceValidationError("governance boundary is unsupported")


def diagnostic_governance_boundary() -> dict[str, Any]:
    return {
        "diagnosticOnly": True,
        "runtimeDecisionAuthority": "NONE",
        "promotionAuthority": "NONE",
        "thresholdChangeAuthority": "NONE",
        "paymentAuthorizationAuthority": "NONE",
        "workflowAuthority": "NONE",
    }


def _timestamp(value: Any, field: str) -> str:
    try:
        normalized = normalize_rfc3339_timestamp(value, field)
    except ValueError as exc:
        raise ExactModelShadowPerformanceValidationError(str(exc)) from exc
    if normalized != value:
        raise ExactModelShadowPerformanceValidationError(f"{field} must be canonical")
    return normalized


def _sha256(value: Any, field: str) -> None:
    if not isinstance(value, str) or len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise ExactModelShadowPerformanceValidationError(f"{field} must be lowercase SHA-256")


def _constant(value: dict[str, Any], field: str, expected: Any) -> None:
    if value.get(field) != expected:
        raise ExactModelShadowPerformanceValidationError(f"{field} is unsupported")


def _exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    extra = sorted(set(value) - expected)
    missing = sorted(expected - set(value))
    if extra:
        raise ExactModelShadowPerformanceValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    if missing:
        raise ExactModelShadowPerformanceValidationError(f"{label} missing required fields: {', '.join(missing)}")

