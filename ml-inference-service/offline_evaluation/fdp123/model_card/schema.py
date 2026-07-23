from __future__ import annotations

from datetime import datetime, timezone
import re
from typing import Any


class Fdp123ModelCardValidationError(ValueError):
    """Raised when FDP-123/FDP-124 Model Card v1 content is invalid or unsafe."""


MODEL_CARD_VERSION = "model-card-v1"
MODEL_CARD_REPORT_TYPE = "MODEL_CARD_V1"
ARTIFACT_SET_VERSION = "model-card-artifact-set-v1"

EXPECTED_EVALUATION_REPORT_TYPE = "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
EXPECTED_SOURCE_ARTIFACT_SET_VERSION = "fdp123-report-artifact-set-v1"
EXPECTED_EVALUATION_SUMMARY_FILENAME = "evaluation_summary.json"
EXPECTED_EVALUATION_MANIFEST_FILENAME = "manifest.json"
MAX_EVALUATION_SUMMARY_BYTES = 262_144
MAX_EVALUATION_MANIFEST_BYTES = 65_536
EXPECTED_DATASET_VERSION = "feedback-dataset-v1"
EXPECTED_DATASET_TIME_BASIS = "FEEDBACK_CREATED_AT"

PRODUCTION_APPROVAL = "NOT_APPROVED"
PROMOTION_STATUS = "NOT_EVALUATED_FOR_PROMOTION"

MAX_COUNT_VALUE = 1_000_000
MAX_WARNINGS = 20
MAX_LIST_ITEMS = 30

ALLOWED_USAGE_MODES = {"SHADOW", "COMPARE", "OFFLINE_EVALUATION"}
FORBIDDEN_USAGE_VALUES = {
    "PRODUCTION",
    "CHAMPION",
    "PRODUCTION_DECISIONING",
    "AUTO_DECLINE",
    "AUTO_APPROVE",
    "AUTO_BLOCK",
    "PAYMENT_AUTHORIZATION",
    "THRESHOLD_CONTROL",
    "MODEL_PROMOTION",
    "MODEL_PROMOTION_APPROVED",
    "PROMOTION_APPROVED",
    "PROMOTION_READY",
    "PRODUCTION_APPROVED",
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
ALLOWED_TRAINING_MODES = {
    "OFFLINE_TRAINED",
    "SYNTHETIC_BASELINE",
    "REFERENCE_MODEL",
    "UNKNOWN_OFFLINE",
}
FORBIDDEN_TRAINING_MODES = {
    "PRODUCTION_APPROVED",
    "LIVE_TRAINING",
    "AUTO_RETRAINED",
}
ALLOWED_REFERENCE_QUALITY = {
    "BOUNDED_ANALYST_FEEDBACK",
    "SYNTHETIC",
    "MIXED",
    "LIMITED_EVIDENCE",
}
FORBIDDEN_REFERENCE_QUALITY = {
    "GROUND_TRUTH",
    "LEGAL_TRUTH",
    "CERTIFIED_LABELS",
    "TRAINING_LABELS",
}
ALLOWED_INTENDED_USE = {
    "SHADOW_FRAUD_RISK_REVIEW",
    "COMPARE_MODE_SIGNAL_REVIEW",
    "OFFLINE_DIAGNOSTIC_ANALYSIS",
    "MODEL_GOVERNANCE_DOCUMENTATION",
    "RULE_VS_ML_REVIEW",
}
REQUIRED_NOT_INTENDED_USE = {
    "NO_AUTOMATIC_TRANSACTION_DECLINE",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_FINAL_BANK_DECISION",
    "NO_AUTOMATIC_CUSTOMER_BLOCKING",
    "NO_PRODUCTION_THRESHOLD_MUTATION",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_CASE_WORKFLOW_AUTOMATION",
    "NO_REGULATORY_CERTIFICATION_CLAIM",
}
REQUIRED_LIMITATIONS = {
    "ANALYST_FEEDBACK_LABELS_ARE_NOT_LEGAL_GROUND_TRUTH",
    "OFFLINE_DIAGNOSTIC_METRICS_ARE_NOT_PRODUCTION_APPROVAL",
    "SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE",
    "PSEUDONYMOUS_REFERENCES_ARE_NOT_ANONYMIZATION",
    "MODEL_CARD_DOES_NOT_APPROVE_PROMOTION",
    "MODEL_CARD_DOES_NOT_AUTHORIZE_AUTOMATIC_DECLINE",
    "MODEL_CARD_DOES_NOT_CHANGE_SCORING_THRESHOLDS",
}
REQUIRED_GOVERNANCE_BOUNDARY = {
    "NO_MODEL_PROMOTION",
    "NO_THRESHOLD_RECOMMENDATION",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_FINAL_DECISIONING",
    "NO_WORKFLOW_AUTOMATION",
    "NO_CASE_CREATION",
    "NO_EXTERNAL_PUBLISHING",
    "NO_PRODUCTION_APPROVAL",
}
MODEL_IDENTITY_METADATA_UNAVAILABLE_LIMITATION = "MODEL_IDENTITY_METADATA_UNAVAILABLE"
REQUIRED_MODEL_CARD_FIELDS = {
    "modelCardVersion",
    "cardType",
    "generatedAt",
    "modelName",
    "modelVersion",
    "modelFamily",
    "trainingMode",
    "featureContractVersion",
    "referenceQuality",
    "allowedUsageModes",
    "productionApproval",
    "promotionStatus",
    "intendedUse",
    "notIntendedUse",
    "evaluationEvidence",
    "metricsSummary",
    "warnings",
    "limitations",
    "governanceBoundary",
}
REQUIRED_EVALUATION_EVIDENCE_FIELDS = {
    "evaluationReportType",
    "evaluationGeneratedAt",
    "evaluationArtifactSetVersion",
    "datasetVersion",
    "datasetTimeBasis",
    "recordsEvaluated",
    "positiveClassCount",
    "negativeClassCount",
    "warnings",
    "sourceManifestSha256",
}
REQUIRED_METRICS_SUMMARY_FIELDS = {
    "alertRecommendedPrecision",
    "alertRecommendedRecall",
    "falsePositiveRate",
    "falseNegativeRate",
}
ALLOWED_METRICS_SUMMARY_FIELDS = REQUIRED_METRICS_SUMMARY_FIELDS

FORBIDDEN_FIELD_NAMES = {
    "transactionid",
    "feedbackid",
    "customerid",
    "correlationid",
    "createdby",
    "evaluationrecordid",
    "transactionreference",
    "notes",
    "rawnotes",
    "rawpayload",
    "rawmlrequest",
    "rawmlresponse",
    "rawfeaturevector",
    "rawevidence",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentdecision",
    "paymentauthorization",
    "promotionrecommended",
    "thresholdrecommendation",
    "productionready",
    "certifiedforproduction",
    "bankcertified",
    "token",
    "secret",
    "password",
}
FORBIDDEN_VALUE_TERMS = set(FORBIDDEN_FIELD_NAMES) | {
    "autodecline",
    "autoapprove",
    "autoblock",
    "champion",
    "productiondecisioning",
    "productionapproved",
    "promotionapproved",
    "promotionready",
}
SAFE_CONTRACT_VALUES = {
    MODEL_CARD_VERSION,
    MODEL_CARD_REPORT_TYPE,
    ARTIFACT_SET_VERSION,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_SOURCE_ARTIFACT_SET_VERSION,
    EXPECTED_DATASET_VERSION,
    EXPECTED_DATASET_TIME_BASIS,
    PRODUCTION_APPROVAL,
    PROMOTION_STATUS,
} | ALLOWED_USAGE_MODES | ALLOWED_MODEL_FAMILIES | ALLOWED_TRAINING_MODES | ALLOWED_REFERENCE_QUALITY
SAFE_NEGATED_MACHINE_CODES = REQUIRED_NOT_INTENDED_USE | REQUIRED_LIMITATIONS | REQUIRED_GOVERNANCE_BOUNDARY

MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
SAFE_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
RFC3339_DATETIME_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$"
)
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
    "password",
}
IDENTITY_FORBIDDEN_CHARS = {"/", "\\", ":", "?", "&", "=", "@", "$", "{", "}", "[", "]", "(", ")"}
FORBIDDEN_DEFAULT_IDENTIFIERS = {"unknown", "v1", "none", "null", "na", "n/a"}


def validate_model_card(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise Fdp123ModelCardValidationError("model card must be an object")
    _reject_unsafe(raw)
    extra = sorted(set(raw) - REQUIRED_MODEL_CARD_FIELDS)
    if extra:
        raise Fdp123ModelCardValidationError(f"model card contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_MODEL_CARD_FIELDS - set(raw))
    if missing:
        raise Fdp123ModelCardValidationError(f"model card missing required fields: {', '.join(missing)}")

    normalized = {
        "modelCardVersion": _required_constant(raw, "modelCardVersion", MODEL_CARD_VERSION),
        "cardType": _required_constant(raw, "cardType", MODEL_CARD_REPORT_TYPE),
        "generatedAt": normalize_rfc3339_timestamp(raw.get("generatedAt"), "generatedAt"),
        "modelName": _safe_identifier(raw, "modelName"),
        "modelVersion": _safe_identifier(raw, "modelVersion"),
        "modelFamily": _bounded_enum(raw, "modelFamily", ALLOWED_MODEL_FAMILIES),
        "trainingMode": _bounded_enum(raw, "trainingMode", ALLOWED_TRAINING_MODES, FORBIDDEN_TRAINING_MODES),
        "featureContractVersion": _safe_identifier(raw, "featureContractVersion"),
        "referenceQuality": _bounded_enum(raw, "referenceQuality", ALLOWED_REFERENCE_QUALITY, FORBIDDEN_REFERENCE_QUALITY),
        "allowedUsageModes": _allowed_usage_modes(raw),
        "productionApproval": _required_constant(raw, "productionApproval", PRODUCTION_APPROVAL),
        "promotionStatus": _required_constant(raw, "promotionStatus", PROMOTION_STATUS),
        "intendedUse": _intended_use(raw),
        "notIntendedUse": _required_machine_code_superset(raw, "notIntendedUse", REQUIRED_NOT_INTENDED_USE),
        "evaluationEvidence": _evaluation_evidence(raw),
        "metricsSummary": _metrics_summary(raw),
        "warnings": _optional_machine_code_list(raw, "warnings", MAX_WARNINGS),
        "limitations": _required_machine_code_superset(raw, "limitations", REQUIRED_LIMITATIONS),
        "governanceBoundary": _required_machine_code_superset(raw, "governanceBoundary", REQUIRED_GOVERNANCE_BOUNDARY),
    }
    if _timestamp_instant(normalized["generatedAt"]) < _timestamp_instant(
            normalized["evaluationEvidence"]["evaluationGeneratedAt"]
    ):
        raise Fdp123ModelCardValidationError("generatedAt must be greater than or equal to evaluationGeneratedAt")
    _validate_unknown_metadata_disclosure(normalized)
    _reject_unsafe(normalized)
    return normalized


def _evaluation_evidence(raw: dict[str, Any]) -> dict[str, Any]:
    value = raw.get("evaluationEvidence")
    if not isinstance(value, dict):
        raise Fdp123ModelCardValidationError("evaluationEvidence must be an object")
    extra = sorted(set(value) - REQUIRED_EVALUATION_EVIDENCE_FIELDS)
    if extra:
        raise Fdp123ModelCardValidationError(f"evaluationEvidence contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_EVALUATION_EVIDENCE_FIELDS - set(value))
    if missing:
        raise Fdp123ModelCardValidationError(f"evaluationEvidence missing required fields: {', '.join(missing)}")
    warnings = _optional_machine_code_list(value, "warnings", MAX_WARNINGS)
    evidence = {
        "evaluationReportType": _required_constant(value, "evaluationReportType", EXPECTED_EVALUATION_REPORT_TYPE),
        "evaluationGeneratedAt": normalize_rfc3339_timestamp(value.get("evaluationGeneratedAt"), "evaluationGeneratedAt"),
        "evaluationArtifactSetVersion": _required_constant(
            value,
            "evaluationArtifactSetVersion",
            EXPECTED_SOURCE_ARTIFACT_SET_VERSION,
        ),
        "datasetVersion": _required_constant(value, "datasetVersion", EXPECTED_DATASET_VERSION),
        "datasetTimeBasis": _required_constant(value, "datasetTimeBasis", EXPECTED_DATASET_TIME_BASIS),
        "recordsEvaluated": _required_count(value, "recordsEvaluated"),
        "positiveClassCount": _required_count(value, "positiveClassCount"),
        "negativeClassCount": _required_count(value, "negativeClassCount"),
        "warnings": warnings,
        "sourceManifestSha256": _sha256(value, "sourceManifestSha256"),
    }
    validate_class_count_integrity(
        evidence["positiveClassCount"],
        evidence["negativeClassCount"],
        evidence["recordsEvaluated"],
    )
    return evidence


def _metrics_summary(raw: dict[str, Any]) -> dict[str, Any]:
    value = raw.get("metricsSummary")
    if not isinstance(value, dict) or not value:
        raise Fdp123ModelCardValidationError("metricsSummary must be a non-empty object")
    extra = sorted(set(value) - ALLOWED_METRICS_SUMMARY_FIELDS)
    if extra:
        raise Fdp123ModelCardValidationError(f"metricsSummary contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_METRICS_SUMMARY_FIELDS - set(value))
    if missing:
        raise Fdp123ModelCardValidationError(f"metricsSummary missing required fields: {', '.join(missing)}")
    summary = {
        "alertRecommendedPrecision": _metric_object(value, "alertRecommendedPrecision"),
        "alertRecommendedRecall": _metric_object(value, "alertRecommendedRecall"),
        "falsePositiveRate": _metric_object(value, "falsePositiveRate"),
        "falseNegativeRate": _metric_object(value, "falseNegativeRate"),
    }
    return summary


def _metric_object(raw: dict[str, Any], field: str) -> dict[str, float | bool | str | None]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise Fdp123ModelCardValidationError(f"metricsSummary.{field} must be a metric object")
    extra = sorted(set(value) - {"available", "value", "reason"})
    if extra:
        raise Fdp123ModelCardValidationError(f"metricsSummary.{field} contains unsupported fields")
    if set(value) != {"available", "value", "reason"}:
        raise Fdp123ModelCardValidationError(f"metricsSummary.{field} must contain available, value, reason")
    available = value["available"]
    metric_value = value["value"]
    reason = value["reason"]
    if not isinstance(available, bool):
        raise Fdp123ModelCardValidationError(f"metricsSummary.{field}.available must be boolean")
    if available:
        if isinstance(metric_value, bool) or not isinstance(metric_value, (int, float)):
            raise Fdp123ModelCardValidationError(f"metricsSummary.{field}.value must be numeric when available")
        if metric_value < 0.0 or metric_value > 1.0:
            raise Fdp123ModelCardValidationError(f"metricsSummary.{field}.value must be in range 0.0..1.0")
        if reason is not None:
            raise Fdp123ModelCardValidationError(f"metricsSummary.{field}.reason must be null when available")
        return {"available": True, "value": float(metric_value), "reason": None}
    if metric_value is not None:
        raise Fdp123ModelCardValidationError(f"metricsSummary.{field}.value must be null when unavailable")
    if not isinstance(reason, str) or MACHINE_CODE_PATTERN.fullmatch(reason) is None:
        raise Fdp123ModelCardValidationError(f"metricsSummary.{field}.reason must be machine-code when unavailable")
    _reject_unsafe_value(reason)
    return {"available": False, "value": None, "reason": reason}


def validate_class_count_integrity(positive_class_count: int, negative_class_count: int, records_evaluated: int) -> None:
    if positive_class_count + negative_class_count != records_evaluated:
        raise Fdp123ModelCardValidationError("positiveClassCount + negativeClassCount must equal recordsEvaluated")


def normalize_rfc3339_timestamp(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise Fdp123ModelCardValidationError(f"{field} must be a non-empty timestamp")
    if len(value) > 128:
        raise Fdp123ModelCardValidationError(f"{field} exceeds maximum length")
    _reject_unsafe_value(value)
    if RFC3339_DATETIME_PATTERN.fullmatch(value) is None:
        raise Fdp123ModelCardValidationError(f"{field} must be an RFC3339 date-time with timezone")
    return _format_utc_timestamp(_timestamp_instant(value))


def _timestamp_instant(value: str) -> datetime:
    parseable = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(parseable)
    except ValueError as exception:
        raise Fdp123ModelCardValidationError("timestamp must be a valid calendar date-time") from exception
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise Fdp123ModelCardValidationError("timestamp must include timezone")
    return parsed.astimezone(timezone.utc)


def _format_utc_timestamp(value: datetime) -> str:
    timespec = "microseconds" if value.microsecond else "seconds"
    return value.astimezone(timezone.utc).isoformat(timespec=timespec).replace("+00:00", "Z")


def _validate_unknown_metadata_disclosure(card: dict[str, Any]) -> None:
    if card["modelFamily"] != "UNKNOWN" and card["trainingMode"] != "UNKNOWN_OFFLINE":
        return
    disclosures = set(card["warnings"]) | set(card["limitations"])
    if MODEL_IDENTITY_METADATA_UNAVAILABLE_LIMITATION not in disclosures:
        raise Fdp123ModelCardValidationError(
            "UNKNOWN model metadata requires MODEL_IDENTITY_METADATA_UNAVAILABLE disclosure"
        )


def _required_constant(raw: dict[str, Any], field: str, expected: str) -> str:
    value = _bounded_string(raw, field, len(expected))
    if value != expected:
        raise Fdp123ModelCardValidationError(f"{field} must be {expected}")
    return value


def _bounded_enum(
        raw: dict[str, Any],
        field: str,
        allowed: set[str],
        forbidden: set[str] | None = None,
) -> str:
    value = _bounded_string(raw, field, 128)
    if MACHINE_CODE_PATTERN.fullmatch(value) is None:
        raise Fdp123ModelCardValidationError(f"{field} must be a machine-code string")
    if forbidden and value in forbidden:
        raise Fdp123ModelCardValidationError(f"{field} has forbidden value")
    if value not in allowed:
        raise Fdp123ModelCardValidationError(f"{field} has unsupported value")
    return value


def _allowed_usage_modes(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "allowedUsageModes", 5)
    rejected = [value for value in values if value in FORBIDDEN_USAGE_VALUES or value not in ALLOWED_USAGE_MODES]
    if rejected:
        raise Fdp123ModelCardValidationError("allowedUsageModes contains unsupported value")
    return values


def _intended_use(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "intendedUse", 10)
    rejected = [value for value in values if value not in ALLOWED_INTENDED_USE]
    if rejected:
        raise Fdp123ModelCardValidationError("intendedUse contains unsupported value")
    return values


def _required_machine_code_superset(raw: dict[str, Any], field: str, required: set[str]) -> list[str]:
    values = _machine_code_list(raw, field, MAX_LIST_ITEMS)
    missing = sorted(required - set(values))
    if missing:
        raise Fdp123ModelCardValidationError(f"{field} missing required values: {', '.join(missing)}")
    return values


def _machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field)
    if not isinstance(value, list) or not value:
        raise Fdp123ModelCardValidationError(f"{field} must be a non-empty list")
    if len(value) > max_items:
        raise Fdp123ModelCardValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise Fdp123ModelCardValidationError(f"{field} must contain machine-code strings")
        if len(item) > 256:
            raise Fdp123ModelCardValidationError(f"{field} contains oversized item")
        _reject_unsafe_value(item)
        result.append(item)
    return sorted(set(result))


def _optional_machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field, [])
    if value is None:
        value = []
    if not isinstance(value, list):
        raise Fdp123ModelCardValidationError(f"{field} must be a list")
    if len(value) > max_items:
        raise Fdp123ModelCardValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise Fdp123ModelCardValidationError(f"{field} must contain machine-code strings")
        if len(item) > 256:
            raise Fdp123ModelCardValidationError(f"{field} contains oversized item")
        _reject_unsafe_value(item)
        result.append(item)
    return sorted(set(result))


def _safe_identifier(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 128)
    compact = _compact(value)
    if compact in FORBIDDEN_DEFAULT_IDENTIFIERS:
        raise Fdp123ModelCardValidationError(f"{field} must be explicit")
    if SAFE_IDENTIFIER_PATTERN.fullmatch(value) is None or ".." in value:
        raise Fdp123ModelCardValidationError(f"{field} must be a safe identifier")
    if any(character in value for character in IDENTITY_FORBIDDEN_CHARS):
        raise Fdp123ModelCardValidationError(f"{field} must not be an artifact location")
    if any(character.isspace() for character in value):
        raise Fdp123ModelCardValidationError(f"{field} must not contain whitespace")
    if any(term in compact for term in IDENTITY_FORBIDDEN_COMPACT_TERMS):
        raise Fdp123ModelCardValidationError(f"{field} must not contain operational location details")
    return value


def _required_count(raw: dict[str, Any], field: str) -> int:
    value = raw.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise Fdp123ModelCardValidationError(f"{field} must be a non-negative integer")
    if value < 0:
        raise Fdp123ModelCardValidationError(f"{field} must be non-negative")
    if value > MAX_COUNT_VALUE:
        raise Fdp123ModelCardValidationError(f"{field} exceeds maximum value")
    return value


def _sha256(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 64)
    if re.fullmatch(r"[a-f0-9]{64}", value) is None:
        raise Fdp123ModelCardValidationError(f"{field} must be sha256 hex")
    return value


def _bounded_string(raw: dict[str, Any], field: str, max_length: int) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise Fdp123ModelCardValidationError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise Fdp123ModelCardValidationError(f"{field} exceeds maximum length")
    _reject_unsafe_value(value)
    return value


def _reject_unsafe(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            compact_key = _compact(str(key))
            if compact_key in FORBIDDEN_FIELD_NAMES:
                raise Fdp123ModelCardValidationError(f"forbidden field: {key}")
            _reject_unsafe(nested)
    elif isinstance(value, list):
        for item in value:
            _reject_unsafe(item)
    elif isinstance(value, str):
        _reject_unsafe_value(value)


def _reject_unsafe_value(value: str) -> None:
    if value in SAFE_CONTRACT_VALUES or value in SAFE_NEGATED_MACHINE_CODES:
        return
    lowered = value.lower()
    if "eval_" in lowered or "txnref_" in lowered or "eval-" in lowered or "txnref-" in lowered:
        raise Fdp123ModelCardValidationError("forbidden pseudonymous identifier prefix")
    compact = _compact(value)
    for term in FORBIDDEN_VALUE_TERMS:
        if term in compact:
            raise Fdp123ModelCardValidationError(f"forbidden value: {value}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())

