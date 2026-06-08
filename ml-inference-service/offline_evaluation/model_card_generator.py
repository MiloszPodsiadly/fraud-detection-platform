from __future__ import annotations

from copy import deepcopy
from typing import Any

from offline_evaluation.model_card_schema import (
    EVALUATION_REPORT_VERSION,
    GOVERNANCE_STATUS,
    MODEL_CARD_TYPE,
    MODEL_CARD_VERSION,
    ModelCardValidationError,
    validate_model_card,
)


REQUIRED_LIMITATIONS = [
    "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
    "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
    "DIAGNOSTIC_ONLY",
    "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_PRODUCTION_DECISIONING_APPROVAL",
    "NO_THRESHOLD_RECOMMENDATION",
    "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
    "OFFLINE_ONLY",
]
DEFAULT_NOT_INTENDED_USE = [
    "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_PRODUCTION_DECISIONING_APPROVAL",
    "NO_THRESHOLD_RECOMMENDATION",
]
ALLOWED_MODEL_METADATA_FIELDS = {
    "modelName",
    "modelVersion",
    "modelFamily",
    "featureContractVersion",
    "intendedUse",
    "notIntendedUse",
    "approvedFor",
}


def build_model_card(evaluation_report: dict[str, Any], model_metadata: dict[str, Any], generated_at: str) -> dict[str, Any]:
    if not isinstance(evaluation_report, dict):
        raise ModelCardValidationError("evaluation report must be an object")
    if not isinstance(model_metadata, dict):
        raise ModelCardValidationError("model metadata must be an object")
    extra_metadata = sorted(set(model_metadata) - ALLOWED_MODEL_METADATA_FIELDS)
    if extra_metadata:
        raise ModelCardValidationError(f"model metadata contains unsupported fields: {', '.join(extra_metadata)}")

    input_summary = _required_object(evaluation_report, "inputSummary")
    export_metadata = _required_object(input_summary, "exportMetadata")
    quality_metrics = _required_object(evaluation_report, "qualityMetrics")
    disagreement_summary = _required_object(evaluation_report, "disagreementSummary")

    model_card = {
        "modelCardVersion": MODEL_CARD_VERSION,
        "cardType": MODEL_CARD_TYPE,
        "generatedAt": generated_at,
        "modelName": model_metadata.get("modelName"),
        "modelVersion": model_metadata.get("modelVersion"),
        "modelFamily": model_metadata.get("modelFamily"),
        "featureContractVersion": model_metadata.get("featureContractVersion"),
        "evaluationReportType": evaluation_report.get("reportType"),
        "evaluationReportVersion": EVALUATION_REPORT_VERSION,
        "evaluationReportGeneratedAt": evaluation_report.get("generatedAt"),
        "datasetTimeBasis": export_metadata.get("timeBasis"),
        "datasetDeduplicationPolicy": export_metadata.get("deduplicationPolicy"),
        "approvedFor": list(model_metadata.get("approvedFor", [])),
        "intendedUse": list(model_metadata.get("intendedUse", [])),
        "notIntendedUse": list(model_metadata.get("notIntendedUse", DEFAULT_NOT_INTENDED_USE)),
        "metricsSummary": _metrics_summary(input_summary, quality_metrics, disagreement_summary),
        "limitations": REQUIRED_LIMITATIONS,
        "warnings": list(evaluation_report.get("warnings", [])),
        "governanceStatus": GOVERNANCE_STATUS,
    }
    return validate_model_card(model_card)


def _metrics_summary(
        input_summary: dict[str, Any],
        quality_metrics: dict[str, Any],
        disagreement_summary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "metricBasis": quality_metrics.get("metricBasis"),
        "diagnosticOnly": True,
        "datasetRecordsRead": input_summary.get("datasetRecordsRead"),
        "recordsAcceptedForEvaluation": input_summary.get("recordsAcceptedForEvaluation"),
        "recordsExcludedNotEvaluationEligible": input_summary.get("recordsExcludedNotEvaluationEligible"),
        "missingMlCount": quality_metrics.get("missingMlCount"),
        "missingRulesCount": quality_metrics.get("missingRulesCount"),
        "missingProjectionCount": input_summary.get("recordsWithMissingProjection"),
        "notEvaluationEligibleCount": quality_metrics.get("notEvaluationEligibleCount"),
        "precisionAtBudget": quality_metrics.get("precisionAtBudget"),
        "recallAtTopK": quality_metrics.get("recallAtTopK"),
        "falsePositiveRate": quality_metrics.get("falsePositiveRate"),
        "mlCaughtRulesMissedCount": quality_metrics.get("mlCaughtRulesMissedCount"),
        "rulesCaughtMlMissedCount": quality_metrics.get("rulesCaughtMlMissedCount"),
        "disagreementSummary": deepcopy(disagreement_summary),
    }


def _required_object(raw: dict[str, Any], field: str) -> dict[str, Any]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise ModelCardValidationError(f"{field} must be an object")
    return value
