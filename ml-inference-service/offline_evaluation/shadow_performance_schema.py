from __future__ import annotations

import re
from typing import Any

from offline_evaluation.fdp123.evaluation_card.schema import (
    EVALUATION_PURPOSE,
    MAX_FDP123_DATASET_RECORDS,
    METRIC_BASIS as EXPECTED_METRIC_BASIS,
    METRICS_SUBJECT,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
    normalize_rfc3339_timestamp,
    _timestamp_instant,
    Fdp123EvaluationCardValidationError,
    validate_evaluation_card,
)
from offline_evaluation.fdp123.evaluation_contract import EVALUATION_SUBJECT
from offline_evaluation.fdp123.report_writer import REPORT_TYPE as EXPECTED_EVALUATION_REPORT_TYPE


class ShadowPerformanceValidationError(ValueError):
    """Raised when Shadow Performance Summary v2 is unsafe or outside FDP-126 bounds."""


REPORT_TYPE = "SHADOW_PERFORMANCE_SUMMARY_V2"
SUMMARY_TYPE = REPORT_TYPE
SUMMARY_VERSION = "shadow-performance-summary-v2"
EXPECTED_EVALUATION_REPORT_VERSION = "FDP-124"
EXPECTED_GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY"
MAX_WARNINGS = 20
MAX_LIMITATIONS = 30
MAX_COUNT_VALUE = MAX_FDP123_DATASET_RECORDS
BANNER = (
    "Shadow performance metrics are offline diagnostics only. They are not model promotion approval, "
    "threshold recommendation, production decisioning approval, payment authorization, "
    "automatic approve / decline / block logic, or analyst recommendation logic."
)
REQUIRED_SUMMARY_FIELDS = {
    "reportType",
    "summaryVersion",
    "generatedAt",
    "evaluationSubject",
    "metricBasis",
    "governance",
    "evaluation",
    "evaluationPopulation",
    "metrics",
    "warnings",
    "limitations",
    "banner",
}
GOVERNANCE_FIELDS = {
    "governanceStatus",
    "diagnosticOnly",
    "notProductionApproval",
    "notPromotionApproval",
    "notThresholdRecommendation",
    "notPaymentAuthorization",
    "notAutomaticDecisioning",
}
EVALUATION_FIELDS = {
    "evaluationCardType",
    "evaluationCardVersion",
    "evaluationPurpose",
    "evaluationReportType",
    "evaluationReportVersion",
    "evaluationReportGeneratedAt",
    "evaluationCardGeneratedAt",
    "evaluationArtifactSetVersion",
    "datasetVersion",
    "datasetTimeBasis",
    "sourceManifestSha256",
    "sourceEvaluationCardManifestSha256",
}
EVALUATION_POPULATION_FIELDS = {
    "recordsEvaluated",
    "positiveClassCount",
    "negativeClassCount",
}
METRIC_FIELDS = {
    "alertRecommendedPrecision",
    "alertRecommendedRecall",
    "falsePositiveRate",
    "falseNegativeRate",
}
METRIC_OBJECT_FIELDS = {"available", "value", "reason"}
LEGACY_V1_ONLY_FIELDS = {
    "summaryType",
    "model",
    "precisionAtBudget",
    "recallAtTopK",
    "disagreementSummary",
    "approvedFor",
    "recordsExcludedNotEvaluationEligible",
    "mlCaughtRulesMissedCount",
    "rulesCaughtMlMissedCount",
    "missingMlCount",
    "missingRulesCount",
    "missingProjectionCount",
    "notEvaluationEligibleCount",
}
MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")
SAFE_CONTRACT_VALUES = {
    REPORT_TYPE,
    SUMMARY_VERSION,
    EXPECTED_GOVERNANCE_STATUS,
    EXPECTED_METRIC_BASIS,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_EVALUATION_REPORT_VERSION,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
    EVALUATION_PURPOSE,
    METRICS_SUBJECT,
    BANNER,
    "DIAGNOSTIC_ONLY",
    "FDP-124",
    "OFFLINE_DIAGNOSTIC",
    "NOT_AVAILABLE",
    "NOT_APPLICABLE",
    "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE",
    "PLATFORM_RECOMMENDATION",
    "ENGINE_INTELLIGENCE_PROJECTION",
    "ENGINE_INTELLIGENCE_PROJECTION_V1",
    "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK",
    "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
    "ANALYST_FEEDBACK_LABELS_ARE_NOT_LEGAL_GROUND_TRUTH",
    "NO_MODEL_PROMOTION_APPROVAL",
    "NO_AUTOMATIC_TRANSACTION_DECLINE",
    "NO_FINAL_BANK_DECISION",
    "NO_AUTOMATIC_CUSTOMER_BLOCKING",
    "NO_PRODUCTION_THRESHOLD_MUTATION",
    "NO_CASE_WORKFLOW_AUTOMATION",
    "NO_REGULATORY_CERTIFICATION_CLAIM",
    "NO_THRESHOLD_RECOMMENDATION",
    "NO_PAYMENT_AUTHORIZATION",
    "NO_FINAL_DECISIONING",
    "NO_WORKFLOW_AUTOMATION",
    "NO_CASE_CREATION",
    "NO_EXTERNAL_PUBLISHING",
    "NO_PRODUCTION_APPROVAL",
    "OFFLINE_DIAGNOSTIC_METRICS_ARE_NOT_PRODUCTION_APPROVAL",
    "METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS",
    "SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE",
    "PSEUDONYMOUS_REFERENCES_ARE_NOT_ANONYMIZATION",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_APPROVE_PROMOTION",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_AUTHORIZE_AUTOMATIC_DECLINE",
    "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_CHANGE_SCORING_THRESHOLDS",
    "OFFLINE_ONLY",
    "LOW_SAMPLE_SIZE",
    "NO_ACTUAL_POSITIVES",
    "NO_ACTUAL_NEGATIVES",
    "NO_PREDICTED_POSITIVES",
    "NO_PREDICTED_NEGATIVES",
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
}
FORBIDDEN_VALUE_TERMS = FORBIDDEN_FIELD_NAMES | {
    "precisionatbudget",
    "recallattopk",
    "modelfamily",
    "modelname",
    "modelversion",
}


def validate_evaluation_card_for_shadow_summary(evaluation_card: dict[str, Any]) -> dict[str, Any]:
    try:
        safe_evaluation_card = validate_evaluation_card(evaluation_card)
    except Fdp123EvaluationCardValidationError as exc:
        raise ShadowPerformanceValidationError(str(exc)) from exc
    if safe_evaluation_card["cardType"] != PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE:
        raise ShadowPerformanceValidationError("evaluation card type is unsupported")
    if safe_evaluation_card["cardVersion"] != PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION:
        raise ShadowPerformanceValidationError("evaluation card version is unsupported")
    if safe_evaluation_card["metricsSubject"] != METRICS_SUBJECT:
        raise ShadowPerformanceValidationError("metricsSubject is unsupported")
    if safe_evaluation_card["metricBasis"] != EXPECTED_METRIC_BASIS:
        raise ShadowPerformanceValidationError("metricBasis is unsupported")
    _reject_unsafe(safe_evaluation_card)
    return safe_evaluation_card


def validate_shadow_performance_summary(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("shadow performance summary must be an object")
    _reject_legacy_v1_fields(raw)
    _reject_unsafe(raw)
    _reject_unknown_or_missing(raw, REQUIRED_SUMMARY_FIELDS, "summary")
    evaluation_subject = _evaluation_subject(raw["evaluationSubject"])
    evaluation_population = _evaluation_population(raw["evaluationPopulation"])
    metrics = _metrics(raw["metrics"])
    normalized = {
        "reportType": _required_constant(raw, "reportType", REPORT_TYPE),
        "summaryVersion": _required_constant(raw, "summaryVersion", SUMMARY_VERSION),
        "generatedAt": normalize_shadow_timestamp(raw.get("generatedAt"), "generatedAt"),
        "evaluationSubject": evaluation_subject,
        "metricBasis": _required_constant(raw, "metricBasis", EXPECTED_METRIC_BASIS),
        "governance": _governance(raw["governance"]),
        "evaluation": _evaluation(raw["evaluation"]),
        "evaluationPopulation": evaluation_population,
        "metrics": metrics,
        "warnings": _machine_code_list(raw, "warnings", MAX_WARNINGS),
        "limitations": _machine_code_list(raw, "limitations", MAX_LIMITATIONS),
        "banner": _required_constant(raw, "banner", BANNER),
    }
    _validate_summary_consistency(normalized)
    _reject_unsafe(normalized)
    return normalized


def _evaluation_subject(raw: Any) -> dict[str, str]:
    if raw != EVALUATION_SUBJECT:
        raise ShadowPerformanceValidationError("evaluationSubject is unsupported")
    return dict(EVALUATION_SUBJECT)


def _governance(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("governance must be an object")
    _reject_unknown_or_missing(raw, GOVERNANCE_FIELDS, "governance")
    result = {
        "governanceStatus": _required_constant(raw, "governanceStatus", EXPECTED_GOVERNANCE_STATUS),
        "diagnosticOnly": _required_boolean(raw, "diagnosticOnly", True),
    }
    for field in sorted(SAFE_NEGATED_FIELDS):
        result[field] = _required_boolean(raw, field, True)
    return {
        "governanceStatus": result["governanceStatus"],
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
        "evaluationCardType": _required_constant(
            raw, "evaluationCardType", PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE
        ),
        "evaluationCardVersion": _required_constant(
            raw, "evaluationCardVersion", PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION
        ),
        "evaluationPurpose": _required_constant(raw, "evaluationPurpose", EVALUATION_PURPOSE),
        "evaluationReportType": _required_constant(raw, "evaluationReportType", EXPECTED_EVALUATION_REPORT_TYPE),
        "evaluationReportVersion": _required_constant(raw, "evaluationReportVersion", EXPECTED_EVALUATION_REPORT_VERSION),
        "evaluationReportGeneratedAt": normalize_shadow_timestamp(
            raw.get("evaluationReportGeneratedAt"), "evaluationReportGeneratedAt"
        ),
        "evaluationCardGeneratedAt": normalize_shadow_timestamp(
            raw.get("evaluationCardGeneratedAt"), "evaluationCardGeneratedAt"
        ),
        "evaluationArtifactSetVersion": _bounded_string(raw, "evaluationArtifactSetVersion", 128),
        "datasetVersion": _bounded_string(raw, "datasetVersion", 128),
        "datasetTimeBasis": _machine_code(raw, "datasetTimeBasis"),
        "sourceManifestSha256": _sha256(raw, "sourceManifestSha256"),
        "sourceEvaluationCardManifestSha256": _sha256(raw, "sourceEvaluationCardManifestSha256"),
    }


def _evaluation_population(raw: Any) -> dict[str, int]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("evaluationPopulation must be an object")
    _reject_unknown_or_missing(raw, EVALUATION_POPULATION_FIELDS, "evaluationPopulation")
    return {
        "recordsEvaluated": _required_count(raw, "recordsEvaluated"),
        "positiveClassCount": _required_count(raw, "positiveClassCount"),
        "negativeClassCount": _required_count(raw, "negativeClassCount"),
    }


def _metrics(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ShadowPerformanceValidationError("metrics must be an object")
    _reject_unknown_or_missing(raw, METRIC_FIELDS, "metrics")
    return {
        "alertRecommendedPrecision": _metric_object(raw, "alertRecommendedPrecision"),
        "alertRecommendedRecall": _metric_object(raw, "alertRecommendedRecall"),
        "falsePositiveRate": _metric_object(raw, "falsePositiveRate"),
        "falseNegativeRate": _metric_object(raw, "falseNegativeRate"),
    }


def _metric_object(raw: dict[str, Any], field: str) -> dict[str, Any]:
    value = raw.get(field)
    if not isinstance(value, dict):
        raise ShadowPerformanceValidationError(f"metrics.{field} must be a metric object")
    _reject_unknown_or_missing(value, METRIC_OBJECT_FIELDS, f"metrics.{field}")
    available = value["available"]
    metric_value = value["value"]
    reason = value["reason"]
    if not isinstance(available, bool):
        raise ShadowPerformanceValidationError(f"metrics.{field}.available must be boolean")
    if available:
        if isinstance(metric_value, bool) or not isinstance(metric_value, (int, float)):
            raise ShadowPerformanceValidationError(f"metrics.{field}.value must be numeric when available")
        if metric_value < 0.0 or metric_value > 1.0:
            raise ShadowPerformanceValidationError(f"metrics.{field}.value must be in range 0.0..1.0")
        if reason is not None:
            raise ShadowPerformanceValidationError(f"metrics.{field}.reason must be null when available")
        return {"available": True, "value": float(metric_value), "reason": None}
    if metric_value is not None:
        raise ShadowPerformanceValidationError(f"metrics.{field}.value must be null when unavailable")
    if not isinstance(reason, str) or MACHINE_CODE_PATTERN.fullmatch(reason) is None:
        raise ShadowPerformanceValidationError(f"metrics.{field}.reason must be machine-code when unavailable")
    _reject_unsafe_value(reason)
    return {"available": False, "value": None, "reason": reason}


def normalize_shadow_timestamp(value: Any, field: str) -> str:
    try:
        return normalize_rfc3339_timestamp(value, field)
    except Fdp123EvaluationCardValidationError as exc:
        raise ShadowPerformanceValidationError(str(exc)) from exc


def _validate_summary_consistency(summary: dict[str, Any]) -> None:
    evaluation_population = summary["evaluationPopulation"]
    if evaluation_population["positiveClassCount"] + evaluation_population["negativeClassCount"] != evaluation_population["recordsEvaluated"]:
        raise ShadowPerformanceValidationError("positiveClassCount + negativeClassCount must equal recordsEvaluated")
    evaluation = summary["evaluation"]
    report_generated_at = _timestamp_instant(evaluation["evaluationReportGeneratedAt"])
    card_generated_at = _timestamp_instant(evaluation["evaluationCardGeneratedAt"])
    summary_generated_at = _timestamp_instant(summary["generatedAt"])
    if card_generated_at < report_generated_at:
        raise ShadowPerformanceValidationError("evaluationCardGeneratedAt must be greater than or equal to evaluationReportGeneratedAt")
    if summary_generated_at < card_generated_at:
        raise ShadowPerformanceValidationError("generatedAt must be greater than or equal to evaluationCardGeneratedAt")


def _reject_unknown_or_missing(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise ShadowPerformanceValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise ShadowPerformanceValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _reject_legacy_v1_fields(raw: dict[str, Any]) -> None:
    present = sorted(LEGACY_V1_ONLY_FIELDS & set(raw))
    if present:
        raise ShadowPerformanceValidationError(f"summary contains legacy V1 fields: {', '.join(present)}")


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


def _sha256(raw: dict[str, Any], field: str) -> str:
    value = _bounded_string(raw, field, 64)
    if SHA256_PATTERN.fullmatch(value) is None:
        raise ShadowPerformanceValidationError(f"{field} must be sha256 hex")
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
    if "eval-" in lowered or "txnref-" in lowered or "eval_" in lowered or "txnref_" in lowered:
        raise ShadowPerformanceValidationError("forbidden pseudonymous identifier prefix")
    compact = _compact(value)
    for safe_field in SAFE_NEGATED_FIELDS:
        compact = compact.replace(_compact(safe_field), "")
    for term in FORBIDDEN_VALUE_TERMS:
        if term in compact:
            raise ShadowPerformanceValidationError(f"forbidden value: {value}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
