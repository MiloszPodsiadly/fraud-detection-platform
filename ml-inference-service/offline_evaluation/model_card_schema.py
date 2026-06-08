from __future__ import annotations

import re
from typing import Any


class ModelCardValidationError(ValueError):
    """Raised when Model Card v1 content is unsafe or outside FDP-104 bounds."""


MODEL_CARD_VERSION = "1.0"
MODEL_CARD_TYPE = "OFFLINE_MODEL_CARD_V1"
GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY"
EXPECTED_EVALUATION_REPORT_TYPE = "PYTHON_ML_EVALUATION_FOUNDATION"
EXPECTED_EVALUATION_REPORT_VERSION = "FDP-103"
EXPECTED_METRIC_BASIS = "bucket_ordered_offline_diagnostic"
EXPECTED_DATASET_TIME_BASIS = "FEEDBACK_SUBMITTED_AT"
EXPECTED_DATASET_DEDUPLICATION_POLICY = "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC"
EVALUATION_REPORT_VERSION = EXPECTED_EVALUATION_REPORT_VERSION

MAX_COUNT_VALUE = 500
MAX_WARNINGS = 10
MAX_LIMITATIONS = 20

ALLOWED_APPROVED_FOR = {"SHADOW", "COMPARE"}
ALLOWED_INTENDED_USE = {
    "SHADOW_FRAUD_RISK_DIAGNOSTICS",
    "COMPARE_MODE_ANALYSIS",
    "OFFLINE_MODEL_REVIEW",
    "RULE_VS_ML_DIAGNOSTICS",
    "MODEL_GOVERNANCE_DOCUMENTATION",
}
REQUIRED_NOT_INTENDED_USE = {
    "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_PRODUCTION_DECISIONING_APPROVAL",
    "NO_THRESHOLD_RECOMMENDATION",
}
ALLOWED_MODEL_FAMILIES = {
    "LOGISTIC_REGRESSION",
    "RANDOM_FOREST",
    "GRADIENT_BOOSTING",
    "XGBOOST",
    "LIGHTGBM",
    "NEURAL_NETWORK",
    "RULE_BASELINE",
    "UNKNOWN",
}
FORBIDDEN_APPROVED_FOR = {
    "PRODUCTION_DECISIONING",
    "AUTO_DECLINE",
    "AUTO_APPROVE",
    "AUTO_BLOCK",
    "PAYMENT_AUTHORIZATION",
    "MODEL_PROMOTION",
    "MODEL_PROMOTION_APPROVED",
    "THRESHOLD_CHANGE",
    "THRESHOLD_CHANGE_APPROVED",
    "CHAMPION",
    "PRODUCTION_APPROVED",
    "RECOMMENDATION_INFLUENCE",
    "OFFLINE_EVALUATION",
}
REQUIRED_MODEL_CARD_FIELDS = {
    "modelCardVersion",
    "cardType",
    "generatedAt",
    "modelName",
    "modelVersion",
    "modelFamily",
    "featureContractVersion",
    "evaluationReportType",
    "evaluationReportVersion",
    "evaluationReportGeneratedAt",
    "datasetTimeBasis",
    "datasetDeduplicationPolicy",
    "approvedFor",
    "intendedUse",
    "notIntendedUse",
    "metricsSummary",
    "limitations",
    "warnings",
    "governanceStatus",
}
ALLOWED_METRICS_SUMMARY_FIELDS = {
    "metricBasis",
    "diagnosticOnly",
    "datasetRecordsRead",
    "recordsAcceptedForEvaluation",
    "recordsExcludedNotEvaluationEligible",
    "missingMlCount",
    "missingRulesCount",
    "missingProjectionCount",
    "notEvaluationEligibleCount",
    "precisionAtBudget",
    "recallAtTopK",
    "falsePositiveRate",
    "mlCaughtRulesMissedCount",
    "rulesCaughtMlMissedCount",
    "disagreementSummary",
}
COUNT_METRIC_FIELDS = {
    "datasetRecordsRead",
    "recordsAcceptedForEvaluation",
    "recordsExcludedNotEvaluationEligible",
    "missingMlCount",
    "missingRulesCount",
    "missingProjectionCount",
    "notEvaluationEligibleCount",
    "mlCaughtRulesMissedCount",
    "rulesCaughtMlMissedCount",
}
RATE_METRIC_FIELDS = {
    "precisionAtBudget",
    "recallAtTopK",
    "falsePositiveRate",
}
ALLOWED_DISAGREEMENT_SUMMARY_FIELDS = {
    "rulesHighMlHigh",
    "rulesHighMlLowOrMedium",
    "rulesLowOrMediumMlHigh",
    "rulesLowOrMediumMlLowOrMedium",
    "rulesMissingMlPresent",
    "mlMissingRulesPresent",
    "bothMissing",
    "notEvaluationEligibleExcluded",
}

FORBIDDEN_FIELD_NAMES = {
    "transactionreference",
    "evaluationrecordid",
    "rawtransactionid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "submittedby",
    "segmentid",
    "segmentbreakdown",
    "customerbreakdown",
    "accountbreakdown",
    "cardbreakdown",
    "devicebreakdown",
    "merchantbreakdown",
    "analystbreakdown",
    "perrecordexamples",
    "rawexamples",
    "correlationid",
    "idempotencykey",
    "requesthash",
    "rawpayload",
    "rawfeaturevector",
    "rawevidence",
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
    "truefraud",
    "finaldecision",
    "paymentauthorization",
    "productionapproved",
    "promotionapproved",
    "promotionready",
    "thresholdrecommendation",
    "deployrecommended",
    "bankcertified",
    "metadata",
    "evaluationreport",
    "rawevaluationreport",
    "rawreport",
    "rawdataset",
}
FORBIDDEN_VALUE_TERMS = set(FORBIDDEN_FIELD_NAMES) | {
    "eval",
    "txnref",
    "approvedforproduction",
    "modelpassed",
    "modelpromotionapproved",
    "thresholdrecommended",
    "automaticdecline",
    "autodecline",
    "autoapprove",
    "autoblock",
    "champion",
}
SAFE_NEGATED_MACHINE_CODES = {
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
SAFE_CONTRACT_VALUES = SAFE_NEGATED_MACHINE_CODES | {
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_REPORT_VERSION,
    EXPECTED_METRIC_BASIS,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_DATASET_DEDUPLICATION_POLICY,
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
    "secret",
    "token",
    "endpoint",
}
IDENTITY_FORBIDDEN_CHARS = {"/", "\\", ":", "?", "&", "=", "@", "$", "{", "}", "[", "]", "(", ")"}


def validate_model_card(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ModelCardValidationError("model card must be an object")
    _reject_unsafe(raw)
    extra = sorted(set(raw) - REQUIRED_MODEL_CARD_FIELDS)
    if extra:
        raise ModelCardValidationError(f"model card contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_MODEL_CARD_FIELDS - set(raw))
    if missing:
        raise ModelCardValidationError(f"model card missing required fields: {', '.join(missing)}")

    normalized = {
        "modelCardVersion": _required_constant(raw, "modelCardVersion", MODEL_CARD_VERSION),
        "cardType": _required_constant(raw, "cardType", MODEL_CARD_TYPE),
        "generatedAt": _bounded_string(raw, "generatedAt", 128),
        "modelName": _safe_identifier(raw, "modelName"),
        "modelVersion": _safe_identifier(raw, "modelVersion"),
        "modelFamily": _model_family(raw),
        "featureContractVersion": _safe_identifier(raw, "featureContractVersion"),
        "evaluationReportType": _required_constant(raw, "evaluationReportType", EXPECTED_EVALUATION_REPORT_TYPE),
        "evaluationReportVersion": _required_constant(raw, "evaluationReportVersion", EXPECTED_EVALUATION_REPORT_VERSION),
        "evaluationReportGeneratedAt": _bounded_string(raw, "evaluationReportGeneratedAt", 128),
        "datasetTimeBasis": _required_constant(raw, "datasetTimeBasis", EXPECTED_DATASET_TIME_BASIS),
        "datasetDeduplicationPolicy": _required_constant(
            raw,
            "datasetDeduplicationPolicy",
            EXPECTED_DATASET_DEDUPLICATION_POLICY,
        ),
        "approvedFor": _approved_for(raw),
        "intendedUse": _intended_use(raw),
        "notIntendedUse": _not_intended_use(raw),
        "metricsSummary": _metrics_summary(raw),
        "limitations": _bounded_machine_code_list(raw, "limitations", MAX_LIMITATIONS),
        "warnings": _bounded_machine_code_list(raw, "warnings", MAX_WARNINGS),
        "governanceStatus": _required_constant(raw, "governanceStatus", GOVERNANCE_STATUS),
    }
    _reject_unsafe(normalized)
    return normalized


def _required_constant(raw: dict[str, Any], field: str, expected: str) -> str:
    value = _bounded_string(raw, field, len(expected))
    if value != expected:
        raise ModelCardValidationError(f"{field} must be {expected}")
    return value


def _required_rate(summary: dict[str, Any], field: str) -> float:
    value = summary.get(field)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ModelCardValidationError(f"metricsSummary.{field} must be a numeric rate")
    if value < 0.0 or value > 1.0:
        raise ModelCardValidationError(f"metricsSummary.{field} must be in range 0.0..1.0")
    return float(value)


def _required_count(summary: dict[str, Any], field: str, max_value: int = MAX_COUNT_VALUE) -> int:
    value = summary.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ModelCardValidationError(f"{field} must be a non-negative integer")
    if value < 0:
        raise ModelCardValidationError(f"{field} must be non-negative")
    if value > max_value:
        raise ModelCardValidationError(f"{field} exceeds maximum value")
    return value


def _safe_identifier(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 128)
    compact = _compact(value)
    if SAFE_IDENTIFIER_PATTERN.fullmatch(value) is None or ".." in value:
        raise ModelCardValidationError(f"{field} must be a safe identifier")
    if any(character in value for character in IDENTITY_FORBIDDEN_CHARS):
        raise ModelCardValidationError(f"{field} must not be an artifact location")
    if any(character.isspace() for character in value):
        raise ModelCardValidationError(f"{field} must not contain whitespace")
    if any(term in compact for term in IDENTITY_FORBIDDEN_COMPACT_TERMS):
        raise ModelCardValidationError(f"{field} must not contain operational location details")
    return value


def _model_family(raw: dict[str, Any]) -> str:
    value = _bounded_string(raw, "modelFamily", 64)
    if MACHINE_CODE_PATTERN.fullmatch(value) is None or value not in ALLOWED_MODEL_FAMILIES:
        raise ModelCardValidationError("modelFamily has unsupported value")
    return value


def _approved_for(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "approvedFor", 5)
    if not values:
        raise ModelCardValidationError("approvedFor must not be empty")
    rejected = [value for value in values if value in FORBIDDEN_APPROVED_FOR or value not in ALLOWED_APPROVED_FOR]
    if rejected:
        raise ModelCardValidationError("approvedFor contains unsupported value")
    return sorted(set(values))


def _intended_use(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "intendedUse", 10)
    rejected = [value for value in values if value not in ALLOWED_INTENDED_USE]
    if rejected:
        raise ModelCardValidationError("intendedUse contains unsupported value")
    return sorted(set(values))


def _not_intended_use(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "notIntendedUse", 20)
    missing = sorted(REQUIRED_NOT_INTENDED_USE - set(values))
    if missing:
        raise ModelCardValidationError(f"notIntendedUse missing required non-goals: {', '.join(missing)}")
    rejected = [value for value in values if value not in SAFE_NEGATED_MACHINE_CODES]
    if rejected:
        raise ModelCardValidationError("notIntendedUse contains unsupported value")
    return sorted(set(values))


def _metrics_summary(raw: dict[str, Any]) -> dict[str, Any]:
    value = raw.get("metricsSummary")
    if not isinstance(value, dict) or not value:
        raise ModelCardValidationError("metricsSummary must be a non-empty object")
    extra = sorted(set(value) - ALLOWED_METRICS_SUMMARY_FIELDS)
    if extra:
        raise ModelCardValidationError(f"metricsSummary contains unsupported fields: {', '.join(extra)}")
    missing = sorted(ALLOWED_METRICS_SUMMARY_FIELDS - set(value))
    if missing:
        raise ModelCardValidationError(f"metricsSummary missing required fields: {', '.join(missing)}")
    if value.get("diagnosticOnly") is not True:
        raise ModelCardValidationError("metricsSummary.diagnosticOnly must be true")

    summary: dict[str, Any] = {
        "metricBasis": _required_constant(value, "metricBasis", EXPECTED_METRIC_BASIS),
        "diagnosticOnly": True,
    }
    for field in sorted(COUNT_METRIC_FIELDS):
        summary[field] = _required_count(value, field)
    for field in sorted(RATE_METRIC_FIELDS):
        summary[field] = _required_rate(value, field)
    summary["disagreementSummary"] = _disagreement_summary(value["disagreementSummary"])
    return {field: summary[field] for field in _metrics_order()}


def _disagreement_summary(raw: Any) -> dict[str, int]:
    if not isinstance(raw, dict) or not raw:
        raise ModelCardValidationError("metricsSummary.disagreementSummary must be a non-empty object")
    extra = sorted(set(raw) - ALLOWED_DISAGREEMENT_SUMMARY_FIELDS)
    if extra:
        raise ModelCardValidationError(f"disagreementSummary contains unsupported fields: {', '.join(extra)}")
    missing = sorted(ALLOWED_DISAGREEMENT_SUMMARY_FIELDS - set(raw))
    if missing:
        raise ModelCardValidationError(f"disagreementSummary missing required fields: {', '.join(missing)}")
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


def _metrics_order() -> list[str]:
    return [
        "metricBasis",
        "diagnosticOnly",
        "datasetRecordsRead",
        "recordsAcceptedForEvaluation",
        "recordsExcludedNotEvaluationEligible",
        "missingMlCount",
        "missingRulesCount",
        "missingProjectionCount",
        "notEvaluationEligibleCount",
        "precisionAtBudget",
        "recallAtTopK",
        "falsePositiveRate",
        "mlCaughtRulesMissedCount",
        "rulesCaughtMlMissedCount",
        "disagreementSummary",
    ]


def _bounded_string(raw: dict[str, Any], field: str, max_length: int) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise ModelCardValidationError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise ModelCardValidationError(f"{field} exceeds maximum length")
    _reject_unsafe_value(value)
    return value


def _machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field)
    if not isinstance(value, list) or not value:
        raise ModelCardValidationError(f"{field} must be a non-empty list")
    if len(value) > max_items:
        raise ModelCardValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise ModelCardValidationError(f"{field} must contain machine-code strings")
        _reject_unsafe_value(item)
        result.append(item)
    return sorted(set(result))


def _bounded_machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    values = _machine_code_list(raw, field, max_items)
    for value in values:
        if len(value) > 256:
            raise ModelCardValidationError(f"{field} contains oversized item")
    return values[:max_items]


def _reject_unsafe(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            compact_key = _compact(str(key))
            if compact_key in FORBIDDEN_FIELD_NAMES:
                raise ModelCardValidationError(f"forbidden field: {key}")
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
        raise ModelCardValidationError("forbidden pseudonymous identifier prefix")
    compact = _compact(value)
    for term in FORBIDDEN_VALUE_TERMS:
        if term in compact:
            raise ModelCardValidationError(f"forbidden value: {value}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
