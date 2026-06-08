from __future__ import annotations

from typing import Any

from offline_evaluation.model_card_schema import (
    EXPECTED_DATASET_DEDUPLICATION_POLICY,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_METRIC_BASIS,
    EVALUATION_REPORT_VERSION,
    GOVERNANCE_STATUS,
    MODEL_CARD_TYPE,
    MODEL_CARD_VERSION,
    REQUIRED_NOT_INTENDED_USE,
    ModelCardValidationError,
    validate_model_card,
)


REQUIRED_LIMITATIONS = [
    "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
    "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
    "DIAGNOSTIC_ONLY",
    "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
    "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_PRODUCTION_DECISIONING_APPROVAL",
    "NO_THRESHOLD_RECOMMENDATION",
    "OFFLINE_ONLY",
]
DEFAULT_NOT_INTENDED_USE = sorted(REQUIRED_NOT_INTENDED_USE)
ALLOWED_MODEL_METADATA_FIELDS = {
    "modelName",
    "modelVersion",
    "modelFamily",
    "featureContractVersion",
    "intendedUse",
    "notIntendedUse",
    "approvedFor",
}
ALLOWED_QUALITY_METRIC_FIELDS = {
    "metricBasis",
    "precisionAtBudget",
    "recallAtTopK",
    "falsePositiveRate",
    "mlCaughtRulesMissedCount",
    "rulesCaughtMlMissedCount",
    "missingMlCount",
    "missingRulesCount",
    "notEvaluationEligibleCount",
}
ALLOWED_DISAGREEMENT_FIELDS = {
    "rulesHighMlHigh",
    "rulesHighMlLowOrMedium",
    "rulesLowOrMediumMlHigh",
    "rulesLowOrMediumMlLowOrMedium",
    "rulesMissingMlPresent",
    "mlMissingRulesPresent",
    "bothMissing",
    "notEvaluationEligibleExcluded",
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
    quality_metrics = _required_quality_metrics(evaluation_report)
    disagreement_summary = _required_disagreement_summary(evaluation_report)
    _require_value(evaluation_report, "reportType", EXPECTED_EVALUATION_REPORT_TYPE)
    _require_value(quality_metrics, "metricBasis", EXPECTED_METRIC_BASIS)
    _require_value(export_metadata, "timeBasis", EXPECTED_DATASET_TIME_BASIS)
    _require_value(export_metadata, "deduplicationPolicy", EXPECTED_DATASET_DEDUPLICATION_POLICY)

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
        "notIntendedUse": _not_intended_use(model_metadata),
        "metricsSummary": _metrics_summary(input_summary, quality_metrics, disagreement_summary),
        "limitations": REQUIRED_LIMITATIONS,
        "warnings": _warnings(evaluation_report),
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
        "disagreementSummary": _disagreement_summary(disagreement_summary),
    }


def _disagreement_summary(raw: dict[str, Any]) -> dict[str, Any]:
    return {
        "rulesHighMlHigh": raw.get("rulesHighMlHigh"),
        "rulesHighMlLowOrMedium": raw.get("rulesHighMlLowOrMedium"),
        "rulesLowOrMediumMlHigh": raw.get("rulesLowOrMediumMlHigh"),
        "rulesLowOrMediumMlLowOrMedium": raw.get("rulesLowOrMediumMlLowOrMedium"),
        "rulesMissingMlPresent": raw.get("rulesMissingMlPresent"),
        "mlMissingRulesPresent": raw.get("mlMissingRulesPresent"),
        "bothMissing": raw.get("bothMissing"),
        "notEvaluationEligibleExcluded": raw.get("notEvaluationEligibleExcluded"),
    }


def _not_intended_use(model_metadata: dict[str, Any]) -> list[str]:
    caller_values = model_metadata.get("notIntendedUse", [])
    if caller_values is None:
        caller_values = []
    if not isinstance(caller_values, list):
        raise ModelCardValidationError("model metadata notIntendedUse must be a list")
    return sorted(set(DEFAULT_NOT_INTENDED_USE) | set(caller_values))


def _warnings(evaluation_report: dict[str, Any]) -> list[str]:
    value = evaluation_report.get("warnings", [])
    if value is None:
        return []
    if not isinstance(value, list):
        raise ModelCardValidationError("warnings must be a list")
    return value


def _required_quality_metrics(evaluation_report: dict[str, Any]) -> dict[str, Any]:
    quality_metrics = _required_object(evaluation_report, "qualityMetrics")
    extra = sorted(set(quality_metrics) - ALLOWED_QUALITY_METRIC_FIELDS)
    if extra:
        raise ModelCardValidationError(f"qualityMetrics contains unsupported fields: {', '.join(extra)}")
    return quality_metrics


def _required_disagreement_summary(evaluation_report: dict[str, Any]) -> dict[str, Any]:
    disagreement_summary = _required_object(evaluation_report, "disagreementSummary")
    extra = sorted(set(disagreement_summary) - ALLOWED_DISAGREEMENT_FIELDS)
    if extra:
        raise ModelCardValidationError(f"disagreementSummary contains unsupported fields: {', '.join(extra)}")
    return disagreement_summary


def _required_object(raw: dict[str, Any], field: str) -> dict[str, Any]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise ModelCardValidationError(f"{field} must be an object")
    return value


def _require_value(raw: dict[str, Any], field: str, expected: str) -> None:
    if raw.get(field) != expected:
        raise ModelCardValidationError(f"{field} must be {expected}")
