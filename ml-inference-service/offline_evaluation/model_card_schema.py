from __future__ import annotations

import re
from copy import deepcopy
from typing import Any


class ModelCardValidationError(ValueError):
    """Raised when Model Card v1 content is unsafe or outside FDP-104 bounds."""


MODEL_CARD_VERSION = "1.0"
MODEL_CARD_TYPE = "OFFLINE_MODEL_CARD_V1"
GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY"
EVALUATION_REPORT_VERSION = "FDP-103"

MAX_WARNINGS = 10
MAX_LIMITATIONS = 20

ALLOWED_APPROVED_FOR = {"SHADOW", "COMPARE", "OFFLINE_EVALUATION"}
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
    "txnref",
    "approvedforproduction",
    "modelpassed",
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
}
SAFE_CONTRACT_VALUES = SAFE_NEGATED_MACHINE_CODES | {
    "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC",
}
MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")


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
        "modelName": _bounded_string(raw, "modelName", 128),
        "modelVersion": _bounded_string(raw, "modelVersion", 128),
        "modelFamily": _model_family(raw),
        "featureContractVersion": _bounded_string(raw, "featureContractVersion", 128),
        "evaluationReportType": _bounded_string(raw, "evaluationReportType", 128),
        "evaluationReportVersion": _bounded_string(raw, "evaluationReportVersion", 64),
        "evaluationReportGeneratedAt": _bounded_string(raw, "evaluationReportGeneratedAt", 128),
        "datasetTimeBasis": _bounded_string(raw, "datasetTimeBasis", 128),
        "datasetDeduplicationPolicy": _bounded_string(raw, "datasetDeduplicationPolicy", 128),
        "approvedFor": _approved_for(raw),
        "intendedUse": _machine_code_list(raw, "intendedUse", 10),
        "notIntendedUse": _machine_code_list(raw, "notIntendedUse", 20),
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


def _metrics_summary(raw: dict[str, Any]) -> dict[str, Any]:
    value = raw.get("metricsSummary")
    if not isinstance(value, dict) or not value:
        raise ModelCardValidationError("metricsSummary must be a non-empty object")
    extra = sorted(set(value) - ALLOWED_METRICS_SUMMARY_FIELDS)
    if extra:
        raise ModelCardValidationError(f"metricsSummary contains unsupported fields: {', '.join(extra)}")
    summary = deepcopy(value)
    if summary.get("diagnosticOnly") is not True:
        raise ModelCardValidationError("metricsSummary.diagnosticOnly must be true")
    disagreement = summary.get("disagreementSummary", {})
    if disagreement is not None and not isinstance(disagreement, dict):
        raise ModelCardValidationError("metricsSummary.disagreementSummary must be an object")
    return summary


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
