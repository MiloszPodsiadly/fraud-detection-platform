from __future__ import annotations

import re
from typing import Any

from offline_evaluation.model_card_schema import (
    EXPECTED_DATASET_DEDUPLICATION_POLICY,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_REPORT_VERSION,
    EXPECTED_METRIC_BASIS,
    GOVERNANCE_STATUS,
    MODEL_CARD_TYPE,
    MODEL_CARD_VERSION,
    ModelCardValidationError,
    validate_model_card,
)


class ShadowPerformanceValidationError(ValueError):
    """Raised when Shadow Performance Summary v1 is unsafe or outside FDP-105 bounds."""


SUMMARY_TYPE = "SHADOW_PERFORMANCE_SUMMARY_V1"
SUMMARY_VERSION = "1.0"
EXPECTED_MODEL_CARD_TYPE = MODEL_CARD_TYPE
EXPECTED_MODEL_CARD_VERSION = MODEL_CARD_VERSION
EXPECTED_GOVERNANCE_STATUS = GOVERNANCE_STATUS
ALLOWED_APPROVED_FOR = {"SHADOW", "COMPARE"}
MAX_WARNINGS = 10
MAX_LIMITATIONS = 20
MAX_COUNT_VALUE = 500
BANNER = (
    "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, "
    "not threshold recommendation, not production decisioning approval, not payment authorization, "
    "not automatic approve / decline / block logic, or not analyst recommendation logic."
)
REQUIRED_SUMMARY_FIELDS = {
    "summaryType",
    "summaryVersion",
    "generatedAt",
    "model",
    "governance",
    "evaluation",
    "evaluationPopulation",
    "metrics",
    "disagreementSummary",
    "warnings",
    "limitations",
    "banner",
}
MODEL_FIELDS = {"modelName", "modelVersion", "modelFamily", "featureContractVersion"}
GOVERNANCE_FIELDS = {
    "governanceStatus",
    "approvedFor",
    "diagnosticOnly",
    "notProductionApproval",
    "notPromotionApproval",
    "notThresholdRecommendation",
    "notPaymentAuthorization",
    "notAutomaticDecisioning",
}
EVALUATION_FIELDS = {
    "evaluationReportType",
    "evaluationReportVersion",
    "metricBasis",
    "datasetTimeBasis",
    "datasetDeduplicationPolicy",
}
EVALUATION_POPULATION_FIELDS = {
    "datasetRecordsRead",
    "recordsAcceptedForEvaluation",
    "recordsExcludedNotEvaluationEligible",
}
METRIC_FIELDS = {
    "precisionAtBudget",
    "recallAtTopK",
    "falsePositiveRate",
    "mlCaughtRulesMissedCount",
    "rulesCaughtMlMissedCount",
    "missingMlCount",
    "missingRulesCount",
    "missingProjectionCount",
    "notEvaluationEligibleCount",
}
RATE_FIELDS = {"precisionAtBudget", "recallAtTopK", "falsePositiveRate"}
COUNT_FIELDS = METRIC_FIELDS - RATE_FIELDS
DISAGREEMENT_FIELDS = {
    "rulesHighMlHigh",
    "rulesHighMlLowOrMedium",
    "rulesLowOrMediumMlHigh",
    "rulesLowOrMediumMlLowOrMedium",
    "rulesMissingMlPresent",
    "mlMissingRulesPresent",
    "bothMissing",
    "notEvaluationEligibleExcluded",
}
MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
SAFE_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
IDENTITY_FORBIDDEN_COMPACT_TERMS = {
    "http",
    "https",
    "s3",
    "gs",
    "file",
    "registry",
    "bucket",
    "endpoint",
    "token",
    "secret",
    "artifact",
    "path",
}
IDENTITY_FORBIDDEN_CHARS = {"/", "\\", ":", "?", "&", "=", "@", "$", "{", "}", "[", "]", "(", ")"}
SAFE_CONTRACT_VALUES = {
    SUMMARY_TYPE,
    SUMMARY_VERSION,
    EXPECTED_MODEL_CARD_TYPE,
    EXPECTED_MODEL_CARD_VERSION,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_REPORT_VERSION,
    EXPECTED_GOVERNANCE_STATUS,
    EXPECTED_METRIC_BASIS,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_DATASET_DEDUPLICATION_POLICY,
    BANNER,
    "SHADOW",
    "COMPARE",
    "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
    "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_THRESHOLD_RECOMMENDATION",
    "NO_PRODUCTION_DECISIONING_APPROVAL",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
    "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
    "OFFLINE_ONLY",
    "DIAGNOSTIC_ONLY",
}
SAFE_NEGATED_FIELDS = {
    "notProductionApproval",
    "notPromotionApproval",
    "notThresholdRecommendation",
    "notPaymentAuthorization",
    "notAutomaticDecisioning",
}
FORBIDDEN_FIELD_NAMES = {
    "evaluationrecordid",
    "transactionreference",
    "rawtransactionid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "submittedby",
    "correlationid",
    "requesthash",
    "idempotencykey",
    "rawpayload",
    "rawfeaturevector",
    "rawmlrequest",
    "rawmlresponse",
    "endpoint",
    "token",
    "secret",
    "stacktrace",
    "exceptionmessage",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentauthorization",
    "productionapproved",
    "promotionapproved",
    "promotionready",
    "thresholdrecommendation",
    "recommendedthreshold",
    "championcandidate",
    "deployrecommendation",
    "rawevaluationreport",
    "rawreport",
    "rawmodelcard",
    "rawdataset",
    "perrecordexamples",
}
FORBIDDEN_VALUE_TERMS = FORBIDDEN_FIELD_NAMES | {
    "eval",
    "txnref",
    "productiondecisioning",
    "modelpromotion",
    "thresholdchange",
    "automaticdecline",
    "autodecline",
    "autoapprove",
    "autoblock",
    "analystrecommendation",
}


def validate_model_card_for_shadow_summary(model_card: dict[str, Any]) -> dict[str, Any]:
    try:
        safe_model_card = validate_model_card(model_card)
    except ModelCardValidationError as exc:
        raise ShadowPerformanceValidationError(str(exc)) from exc
    if safe_model_card["cardType"] != EXPECTED_MODEL_CARD_TYPE:
        raise ShadowPerformanceValidationError("model card type is unsupported")
    if safe_model_card["modelCardVersion"] != EXPECTED_MODEL_CARD_VERSION:
        raise ShadowPerformanceValidationError("model card version is unsupported")
    if safe_model_card["governanceStatus"] != EXPECTED_GOVERNANCE_STATUS:
        raise ShadowPerformanceValidationError("governanceStatus must be DIAGNOSTIC_ONLY")
    if set(safe_model_card["approvedFor"]) - ALLOWED_APPROVED_FOR:
        raise ShadowPerformanceValidationError("approvedFor contains unsupported value")
    metrics = safe_model_card["metricsSummary"]
    if metrics["metricBasis"] != EXPECTED_METRIC_BASIS:
        raise ShadowPerformanceValidationError("metricBasis is unsupported")
    if safe_model_card["datasetTimeBasis"] != EXPECTED_DATASET_TIME_BASIS:
        raise ShadowPerformanceValidationError("datasetTimeBasis is unsupported")
    if safe_model_card["datasetDeduplicationPolicy"] != EXPECTED_DATASET_DEDUPLICATION_POLICY:
        raise ShadowPerformanceValidationError("datasetDeduplicationPolicy is unsupported")
    _reject_unsafe(safe_model_card)
    return safe_model_card


def validate_shadow_performance_summary(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("shadow performance summary must be an object")
    _reject_unsafe(raw)
    _reject_unknown_or_missing(raw, REQUIRED_SUMMARY_FIELDS, "summary")
    evaluation_population = _evaluation_population(raw["evaluationPopulation"])
    metrics = _metrics(raw["metrics"])
    disagreement_summary = _disagreement_summary(raw["disagreementSummary"])
    _validate_summary_consistency(evaluation_population, metrics, disagreement_summary)
    normalized = {
        "summaryType": _required_constant(raw, "summaryType", SUMMARY_TYPE),
        "summaryVersion": _required_constant(raw, "summaryVersion", SUMMARY_VERSION),
        "generatedAt": _bounded_string(raw, "generatedAt", 128),
        "model": _model(raw["model"]),
        "governance": _governance(raw["governance"]),
        "evaluation": _evaluation(raw["evaluation"]),
        "evaluationPopulation": evaluation_population,
        "metrics": metrics,
        "disagreementSummary": disagreement_summary,
        "warnings": _machine_code_list(raw, "warnings", MAX_WARNINGS),
        "limitations": _machine_code_list(raw, "limitations", MAX_LIMITATIONS),
        "banner": _required_constant(raw, "banner", BANNER),
    }
    _reject_unsafe(normalized)
    return normalized


def _model(raw: Any) -> dict[str, str]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("model must be an object")
    _reject_unknown_or_missing(raw, MODEL_FIELDS, "model")
    return {
        "modelName": _safe_identifier(raw, "modelName"),
        "modelVersion": _safe_identifier(raw, "modelVersion"),
        "modelFamily": _machine_code(raw, "modelFamily"),
        "featureContractVersion": _safe_identifier(raw, "featureContractVersion"),
    }


def _governance(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("governance must be an object")
    _reject_unknown_or_missing(raw, GOVERNANCE_FIELDS, "governance")
    approved_for = _machine_code_list(raw, "approvedFor", 2)
    if not approved_for or set(approved_for) - ALLOWED_APPROVED_FOR:
        raise ShadowPerformanceValidationError("approvedFor contains unsupported value")
    result = {
        "governanceStatus": _required_constant(raw, "governanceStatus", EXPECTED_GOVERNANCE_STATUS),
        "approvedFor": approved_for,
        "diagnosticOnly": _required_boolean(raw, "diagnosticOnly", True),
    }
    for field in sorted(SAFE_NEGATED_FIELDS):
        result[field] = _required_boolean(raw, field, True)
    return {
        "governanceStatus": result["governanceStatus"],
        "approvedFor": result["approvedFor"],
        "diagnosticOnly": result["diagnosticOnly"],
        "notProductionApproval": result["notProductionApproval"],
        "notPromotionApproval": result["notPromotionApproval"],
        "notThresholdRecommendation": result["notThresholdRecommendation"],
        "notPaymentAuthorization": result["notPaymentAuthorization"],
        "notAutomaticDecisioning": result["notAutomaticDecisioning"],
    }


def _evaluation(raw: Any) -> dict[str, str]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("evaluation must be an object")
    _reject_unknown_or_missing(raw, EVALUATION_FIELDS, "evaluation")
    return {
        "evaluationReportType": _required_constant(raw, "evaluationReportType", EXPECTED_EVALUATION_REPORT_TYPE),
        "evaluationReportVersion": _required_constant(raw, "evaluationReportVersion", EXPECTED_EVALUATION_REPORT_VERSION),
        "metricBasis": _required_constant(raw, "metricBasis", EXPECTED_METRIC_BASIS),
        "datasetTimeBasis": _required_constant(raw, "datasetTimeBasis", EXPECTED_DATASET_TIME_BASIS),
        "datasetDeduplicationPolicy": _required_constant(
            raw,
            "datasetDeduplicationPolicy",
            EXPECTED_DATASET_DEDUPLICATION_POLICY,
        ),
    }


def _evaluation_population(raw: Any) -> dict[str, int]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("evaluationPopulation must be an object")
    _reject_unknown_or_missing(raw, EVALUATION_POPULATION_FIELDS, "evaluationPopulation")
    dataset_records = _required_count(raw, "datasetRecordsRead")
    accepted = _required_count(raw, "recordsAcceptedForEvaluation")
    excluded_not_eligible = _required_count(raw, "recordsExcludedNotEvaluationEligible")
    if accepted > dataset_records:
        raise ShadowPerformanceValidationError("recordsAcceptedForEvaluation must not exceed datasetRecordsRead")
    if excluded_not_eligible > dataset_records:
        raise ShadowPerformanceValidationError("recordsExcludedNotEvaluationEligible must not exceed datasetRecordsRead")
    if accepted + excluded_not_eligible > dataset_records:
        raise ShadowPerformanceValidationError(
            "recordsAcceptedForEvaluation plus recordsExcludedNotEvaluationEligible must not exceed datasetRecordsRead"
        )
    return {
        "datasetRecordsRead": dataset_records,
        "recordsAcceptedForEvaluation": accepted,
        "recordsExcludedNotEvaluationEligible": excluded_not_eligible,
    }


def _metrics(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("metrics must be an object")
    _reject_unknown_or_missing(raw, METRIC_FIELDS, "metrics")
    return {
        "precisionAtBudget": _required_rate(raw, "precisionAtBudget"),
        "recallAtTopK": _required_rate(raw, "recallAtTopK"),
        "falsePositiveRate": _required_rate(raw, "falsePositiveRate"),
        "mlCaughtRulesMissedCount": _required_count(raw, "mlCaughtRulesMissedCount"),
        "rulesCaughtMlMissedCount": _required_count(raw, "rulesCaughtMlMissedCount"),
        "missingMlCount": _required_count(raw, "missingMlCount"),
        "missingRulesCount": _required_count(raw, "missingRulesCount"),
        "missingProjectionCount": _required_count(raw, "missingProjectionCount"),
        "notEvaluationEligibleCount": _required_count(raw, "notEvaluationEligibleCount"),
    }


def _validate_summary_consistency(
    evaluation_population: dict[str, int],
    metrics: dict[str, Any],
    disagreement_summary: dict[str, int],
) -> None:
    dataset_records = evaluation_population["datasetRecordsRead"]
    excluded_not_eligible = evaluation_population["recordsExcludedNotEvaluationEligible"]
    if metrics["notEvaluationEligibleCount"] != excluded_not_eligible:
        raise ShadowPerformanceValidationError(
            "metrics.notEvaluationEligibleCount must match recordsExcludedNotEvaluationEligible"
        )
    for field in sorted(COUNT_FIELDS):
        if metrics[field] > dataset_records:
            raise ShadowPerformanceValidationError(f"metrics.{field} must not exceed datasetRecordsRead")
    if sum(disagreement_summary.values()) > dataset_records:
        raise ShadowPerformanceValidationError("disagreementSummary total must not exceed datasetRecordsRead")


def _disagreement_summary(raw: Any) -> dict[str, int]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("disagreementSummary must be an object")
    _reject_unknown_or_missing(raw, DISAGREEMENT_FIELDS, "disagreementSummary")
    return {
        "rulesHighMlHigh": _required_count(raw, "rulesHighMlHigh"),
        "rulesHighMlLowOrMedium": _required_count(raw, "rulesHighMlLowOrMedium"),
        "rulesLowOrMediumMlHigh": _required_count(raw, "rulesLowOrMediumMlHigh"),
        "rulesLowOrMediumMlLowOrMedium": _required_count(raw, "rulesLowOrMediumMlLowOrMedium"),
        "rulesMissingMlPresent": _required_count(raw, "rulesMissingMlPresent"),
        "mlMissingRulesPresent": _required_count(raw, "mlMissingRulesPresent"),
        "bothMissing": _required_count(raw, "bothMissing"),
        "notEvaluationEligibleExcluded": _required_count(raw, "notEvaluationEligibleExcluded"),
    }


def _reject_unknown_or_missing(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise ShadowPerformanceValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise ShadowPerformanceValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _required_constant(raw: dict[str, Any], field: str, expected: str) -> str:
    value = _bounded_string(raw, field, len(expected))
    if value != expected:
        raise ShadowPerformanceValidationError(f"{field} must be {expected}")
    return value


def _required_boolean(raw: dict[str, Any], field: str, expected: bool) -> bool:
    value = raw.get(field)
    if value is not expected:
        raise ShadowPerformanceValidationError(f"{field} must be {expected}")
    return value


def _required_rate(raw: dict[str, Any], field: str) -> float:
    value = raw.get(field)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ShadowPerformanceValidationError(f"{field} must be a numeric rate")
    if value < 0.0 or value > 1.0:
        raise ShadowPerformanceValidationError(f"{field} must be in range 0.0..1.0")
    return float(value)


def _required_count(raw: dict[str, Any], field: str) -> int:
    value = raw.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ShadowPerformanceValidationError(f"{field} must be a non-negative integer")
    if value < 0:
        raise ShadowPerformanceValidationError(f"{field} must be non-negative")
    if value > MAX_COUNT_VALUE:
        raise ShadowPerformanceValidationError(f"{field} exceeds maximum value")
    return value


def _machine_code(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 128)
    if MACHINE_CODE_PATTERN.fullmatch(value) is None:
        raise ShadowPerformanceValidationError(f"{field} must be a machine-code string")
    return value


def _machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field, [])
    if value is None:
        value = []
    if not isinstance(value, list):
        raise ShadowPerformanceValidationError(f"{field} must be a list")
    if len(value) > max_items:
        raise ShadowPerformanceValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise ShadowPerformanceValidationError(f"{field} must contain machine-code strings")
        if len(item) > 256:
            raise ShadowPerformanceValidationError(f"{field} contains oversized item")
        _reject_unsafe_value(item)
        result.append(item)
    return sorted(set(result))


def _safe_identifier(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 128)
    compact = _compact(value)
    if SAFE_IDENTIFIER_PATTERN.fullmatch(value) is None or ".." in value:
        raise ShadowPerformanceValidationError(f"{field} must be a safe identifier")
    if any(character in value for character in IDENTITY_FORBIDDEN_CHARS):
        raise ShadowPerformanceValidationError(f"{field} must not be an artifact location")
    if any(character.isspace() for character in value):
        raise ShadowPerformanceValidationError(f"{field} must not contain whitespace")
    if any(term in compact for term in IDENTITY_FORBIDDEN_COMPACT_TERMS):
        raise ShadowPerformanceValidationError(f"{field} must not contain operational location details")
    return value


def _bounded_string(raw: dict[str, Any], field: str, max_length: int) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise ShadowPerformanceValidationError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise ShadowPerformanceValidationError(f"{field} exceeds maximum length")
    _reject_unsafe_value(value)
    return value


def _reject_unsafe(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            if _compact(str(key)) in FORBIDDEN_FIELD_NAMES:
                raise ShadowPerformanceValidationError(f"forbidden field: {key}")
            _reject_unsafe(nested)
    elif isinstance(value, list):
        for item in value:
            _reject_unsafe(item)
    elif isinstance(value, str):
        _reject_unsafe_value(value)


def _reject_unsafe_value(value: str) -> None:
    if value in SAFE_CONTRACT_VALUES:
        return
    lowered = value.lower()
    if "eval-" in lowered or "txnref-" in lowered:
        raise ShadowPerformanceValidationError("forbidden pseudonymous identifier prefix")
    compact = _compact(value)
    for safe_field in SAFE_NEGATED_FIELDS:
        compact = compact.replace(_compact(safe_field), "")
    for term in FORBIDDEN_VALUE_TERMS:
        if term in compact:
            raise ShadowPerformanceValidationError(f"forbidden value: {value}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
