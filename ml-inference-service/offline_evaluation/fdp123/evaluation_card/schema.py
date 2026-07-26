from __future__ import annotations

import re
from typing import Any

from offline_evaluation.json_contract import JsonContractError, require_finite_number
from offline_evaluation.fdp123.dataset_schema import (
    DATASET_TIME_BASIS as EXPECTED_DATASET_TIME_BASIS,
    DATASET_VERSION as EXPECTED_DATASET_VERSION,
    MAX_DATASET_RECORDS as MAX_FDP123_DATASET_RECORDS,
)
from offline_evaluation.fdp123.evaluation_contract import (
    EVALUATION_FEATURE_CONTRACT_VERSION,
    EVALUATION_IDENTITY_COMPLETENESS,
    EVALUATION_MODEL_ARTIFACT_SHA256,
    EVALUATION_MODEL_IDENTITY,
    EVALUATION_SOURCE_COMPONENT,
    EVALUATION_SOURCE_VERSION,
    EVALUATION_SUBJECT,
    EVALUATION_SUBJECT_TYPE,
    METRIC_BASIS,
    METRICS_SUBJECT,
)
from offline_evaluation.fdp123.report_contract import (
    ARTIFACT_SET_VERSION as EXPECTED_SOURCE_ARTIFACT_SET_VERSION,
    REPORT_TYPE as EXPECTED_EVALUATION_REPORT_TYPE,
)
from offline_evaluation.fdp123.evaluation_card.safety_policy import (
    EvaluationCardSafetyPolicyError,
    reject_unsafe_policy_value,
    reject_unsafe_structure,
)
from offline_evaluation.fdp123.timestamp_contract import (
    TimestampContractError,
    normalize_rfc3339_timestamp as _normalize_rfc3339_timestamp,
    timestamp_instant as _timestamp_instant,
)


class Fdp123EvaluationCardValidationError(ValueError):
    """Raised when FDP-123/FDP-124 Platform Recommendation Evaluation Card v1 content is invalid or unsafe."""


PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION = "platform-recommendation-evaluation-card-v1"
PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE = "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1"
ARTIFACT_SET_VERSION = "platform-recommendation-evaluation-card-artifact-set-v1"

EXPECTED_EVALUATION_SUMMARY_FILENAME = "evaluation_summary.json"
EXPECTED_EVALUATION_MANIFEST_FILENAME = "manifest.json"
MAX_EVALUATION_SUMMARY_BYTES = 262_144
MAX_EVALUATION_MANIFEST_BYTES = 65_536

EVALUATION_PURPOSE = "OFFLINE_DIAGNOSTIC"
NO_AUTHORITY = "NONE"

MAX_COUNT_VALUE = MAX_FDP123_DATASET_RECORDS
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
ALLOWED_INTENDED_USE = {
    "SHADOW_FRAUD_RISK_REVIEW",
    "COMPARE_MODE_SIGNAL_REVIEW",
    "OFFLINE_DIAGNOSTIC_ANALYSIS",
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
    "METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS",
    "SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE",
    "PSEUDONYMOUS_REFERENCES_ARE_NOT_ANONYMIZATION",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_APPROVE_PROMOTION",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_AUTHORIZE_AUTOMATIC_DECLINE",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_CHANGE_SCORING_THRESHOLDS",
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
REQUIRED_EVALUATION_SUBJECT_FIELDS = set(EVALUATION_SUBJECT)
REQUIRED_PLATFORM_RECOMMENDATION_EVALUATION_CARD_FIELDS = {
    "cardVersion",
    "cardType",
    "generatedAt",
    "evaluationSubject",
    "metricsSubject",
    "metricBasis",
    "allowedUsageModes",
    "evaluationPurpose",
    "runtimeDecisionAuthority",
    "promotionAuthority",
    "thresholdChangeAuthority",
    "paymentAuthorizationAuthority",
    "workflowAuthority",
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

SAFE_CONTRACT_VALUES = {
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    ARTIFACT_SET_VERSION,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_SOURCE_ARTIFACT_SET_VERSION,
    EXPECTED_DATASET_VERSION,
    EXPECTED_DATASET_TIME_BASIS,
    EVALUATION_SUBJECT_TYPE,
    EVALUATION_SOURCE_COMPONENT,
    EVALUATION_SOURCE_VERSION,
    EVALUATION_FEATURE_CONTRACT_VERSION,
    EVALUATION_MODEL_IDENTITY,
    EVALUATION_MODEL_ARTIFACT_SHA256,
    EVALUATION_IDENTITY_COMPLETENESS,
    METRICS_SUBJECT,
    METRIC_BASIS,
    EVALUATION_PURPOSE,
    NO_AUTHORITY,
} | ALLOWED_USAGE_MODES
SAFE_NEGATED_MACHINE_CODES = REQUIRED_NOT_INTENDED_USE | REQUIRED_LIMITATIONS | REQUIRED_GOVERNANCE_BOUNDARY

MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
def validate_evaluation_card(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise Fdp123EvaluationCardValidationError("evaluation card must be an object")
    _reject_unsafe(raw)
    extra = sorted(set(raw) - REQUIRED_PLATFORM_RECOMMENDATION_EVALUATION_CARD_FIELDS)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"evaluation card contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_PLATFORM_RECOMMENDATION_EVALUATION_CARD_FIELDS - set(raw))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"evaluation card missing required fields: {', '.join(missing)}")

    normalized = {
        "cardVersion": _required_constant(raw, "cardVersion", PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION),
        "cardType": _required_constant(raw, "cardType", PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE),
        "generatedAt": _required_timestamp(raw.get("generatedAt"), "generatedAt"),
        "evaluationSubject": _evaluation_subject(raw),
        "metricsSubject": _required_constant(raw, "metricsSubject", METRICS_SUBJECT),
        "metricBasis": _required_constant(raw, "metricBasis", METRIC_BASIS),
        "allowedUsageModes": _allowed_usage_modes(raw),
        "evaluationPurpose": _required_constant(raw, "evaluationPurpose", EVALUATION_PURPOSE),
        "runtimeDecisionAuthority": _required_constant(raw, "runtimeDecisionAuthority", NO_AUTHORITY),
        "promotionAuthority": _required_constant(raw, "promotionAuthority", NO_AUTHORITY),
        "thresholdChangeAuthority": _required_constant(raw, "thresholdChangeAuthority", NO_AUTHORITY),
        "paymentAuthorizationAuthority": _required_constant(raw, "paymentAuthorizationAuthority", NO_AUTHORITY),
        "workflowAuthority": _required_constant(raw, "workflowAuthority", NO_AUTHORITY),
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
        raise Fdp123EvaluationCardValidationError("generatedAt must be greater than or equal to evaluationGeneratedAt")
    _reject_unsafe(normalized)
    return normalized


def _evaluation_subject(raw: dict[str, Any]) -> dict[str, str]:
    value = raw.get("evaluationSubject")
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError("evaluationSubject must be an object")
    extra = sorted(set(value) - REQUIRED_EVALUATION_SUBJECT_FIELDS)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"evaluationSubject contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_EVALUATION_SUBJECT_FIELDS - set(value))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"evaluationSubject missing required fields: {', '.join(missing)}")
    return {
        field: _required_constant(value, field, expected)
        for field, expected in EVALUATION_SUBJECT.items()
    }


def _evaluation_evidence(raw: dict[str, Any]) -> dict[str, Any]:
    value = raw.get("evaluationEvidence")
    if not isinstance(value, dict):
        raise Fdp123EvaluationCardValidationError("evaluationEvidence must be an object")
    extra = sorted(set(value) - REQUIRED_EVALUATION_EVIDENCE_FIELDS)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"evaluationEvidence contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_EVALUATION_EVIDENCE_FIELDS - set(value))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"evaluationEvidence missing required fields: {', '.join(missing)}")
    warnings = _optional_machine_code_list(value, "warnings", MAX_WARNINGS)
    evidence = {
        "evaluationReportType": _required_constant(value, "evaluationReportType", EXPECTED_EVALUATION_REPORT_TYPE),
        "evaluationGeneratedAt": _required_timestamp(value.get("evaluationGeneratedAt"), "evaluationGeneratedAt"),
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
        raise Fdp123EvaluationCardValidationError("metricsSummary must be a non-empty object")
    extra = sorted(set(value) - ALLOWED_METRICS_SUMMARY_FIELDS)
    if extra:
        raise Fdp123EvaluationCardValidationError(f"metricsSummary contains unsupported fields: {', '.join(extra)}")
    missing = sorted(REQUIRED_METRICS_SUMMARY_FIELDS - set(value))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"metricsSummary missing required fields: {', '.join(missing)}")
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
        raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field} must be a metric object")
    extra = sorted(set(value) - {"available", "value", "reason"})
    if extra:
        raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field} contains unsupported fields")
    if set(value) != {"available", "value", "reason"}:
        raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field} must contain available, value, reason")
    available = value["available"]
    metric_value = value["value"]
    reason = value["reason"]
    if not isinstance(available, bool):
        raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field}.available must be boolean")
    if available:
        try:
            metric_value = require_finite_number(metric_value, f"metricsSummary.{field}.value")
        except JsonContractError as exc:
            raise Fdp123EvaluationCardValidationError(
                f"metricsSummary.{field}.value must be numeric when available"
            ) from exc
        if metric_value < 0.0 or metric_value > 1.0:
            raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field}.value must be in range 0.0..1.0")
        if reason is not None:
            raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field}.reason must be null when available")
        return {"available": True, "value": metric_value, "reason": None}
    if metric_value is not None:
        raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field}.value must be null when unavailable")
    if not isinstance(reason, str) or MACHINE_CODE_PATTERN.fullmatch(reason) is None:
        raise Fdp123EvaluationCardValidationError(f"metricsSummary.{field}.reason must be machine-code when unavailable")
    _reject_unsafe_value(reason)
    return {"available": False, "value": None, "reason": reason}


def validate_class_count_integrity(positive_class_count: int, negative_class_count: int, records_evaluated: int) -> None:
    if positive_class_count + negative_class_count != records_evaluated:
        raise Fdp123EvaluationCardValidationError("positiveClassCount + negativeClassCount must equal recordsEvaluated")


def _required_timestamp(value: Any, field: str) -> str:
    try:
        normalized = _normalize_rfc3339_timestamp(value, field)
    except TimestampContractError as exc:
        raise Fdp123EvaluationCardValidationError(str(exc)) from exc
    _reject_unsafe_value(normalized)
    return normalized


def _required_constant(raw: dict[str, Any], field: str, expected: str) -> str:
    value = _bounded_string(raw, field, len(expected))
    if value != expected:
        raise Fdp123EvaluationCardValidationError(f"{field} must be {expected}")
    return value


def _allowed_usage_modes(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "allowedUsageModes", 5)
    rejected = [value for value in values if value in FORBIDDEN_USAGE_VALUES or value not in ALLOWED_USAGE_MODES]
    if rejected:
        raise Fdp123EvaluationCardValidationError("allowedUsageModes contains unsupported value")
    return values


def _intended_use(raw: dict[str, Any]) -> list[str]:
    values = _machine_code_list(raw, "intendedUse", 10)
    rejected = [value for value in values if value not in ALLOWED_INTENDED_USE]
    if rejected:
        raise Fdp123EvaluationCardValidationError("intendedUse contains unsupported value")
    return values


def _required_machine_code_superset(raw: dict[str, Any], field: str, required: set[str]) -> list[str]:
    values = _machine_code_list(raw, field, MAX_LIST_ITEMS)
    missing = sorted(required - set(values))
    if missing:
        raise Fdp123EvaluationCardValidationError(f"{field} missing required values: {', '.join(missing)}")
    return values


def _machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field)
    if not isinstance(value, list) or not value:
        raise Fdp123EvaluationCardValidationError(f"{field} must be a non-empty list")
    if len(value) > max_items:
        raise Fdp123EvaluationCardValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise Fdp123EvaluationCardValidationError(f"{field} must contain machine-code strings")
        if len(item) > 256:
            raise Fdp123EvaluationCardValidationError(f"{field} contains oversized item")
        _reject_unsafe_value(item)
        result.append(item)
    if len(set(result)) != len(result):
        raise Fdp123EvaluationCardValidationError(f"{field} contains duplicate values")
    return sorted(result)


def _optional_machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field, [])
    if value is None:
        value = []
    if not isinstance(value, list):
        raise Fdp123EvaluationCardValidationError(f"{field} must be a list")
    if len(value) > max_items:
        raise Fdp123EvaluationCardValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise Fdp123EvaluationCardValidationError(f"{field} must contain machine-code strings")
        if len(item) > 256:
            raise Fdp123EvaluationCardValidationError(f"{field} contains oversized item")
        _reject_unsafe_value(item)
        result.append(item)
    if len(set(result)) != len(result):
        raise Fdp123EvaluationCardValidationError(f"{field} contains duplicate values")
    return sorted(result)


def _required_count(raw: dict[str, Any], field: str) -> int:
    value = raw.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise Fdp123EvaluationCardValidationError(f"{field} must be a non-negative integer")
    if value < 0:
        raise Fdp123EvaluationCardValidationError(f"{field} must be non-negative")
    if value > MAX_COUNT_VALUE:
        raise Fdp123EvaluationCardValidationError(f"{field} exceeds maximum value")
    return value


def _sha256(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 64)
    if re.fullmatch(r"[a-f0-9]{64}", value) is None:
        raise Fdp123EvaluationCardValidationError(f"{field} must be sha256 hex")
    return value


def _bounded_string(raw: dict[str, Any], field: str, max_length: int) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise Fdp123EvaluationCardValidationError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise Fdp123EvaluationCardValidationError(f"{field} exceeds maximum length")
    _reject_unsafe_value(value)
    return value


def _reject_unsafe(value: Any) -> None:
    try:
        reject_unsafe_structure(value, safe_values=SAFE_CONTRACT_VALUES | SAFE_NEGATED_MACHINE_CODES)
    except EvaluationCardSafetyPolicyError as exc:
        raise Fdp123EvaluationCardValidationError(str(exc)) from exc


def _reject_unsafe_value(value: str) -> None:
    try:
        reject_unsafe_policy_value(value, safe_values=SAFE_CONTRACT_VALUES | SAFE_NEGATED_MACHINE_CODES)
    except EvaluationCardSafetyPolicyError as exc:
        raise Fdp123EvaluationCardValidationError(str(exc)) from exc
