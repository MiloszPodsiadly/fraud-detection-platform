from __future__ import annotations

import json
import re
from typing import Any

from offline_evaluation.fdp123.evaluation_card.schema import (
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
    Fdp123EvaluationCardValidationError,
    normalize_rfc3339_timestamp,
    _timestamp_instant,
)
from offline_evaluation.shadow_performance_schema import (
    BANNER as SHADOW_PERFORMANCE_BANNER,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_GOVERNANCE_STATUS,
    EXPECTED_METRIC_BASIS,
    REPORT_TYPE as SHADOW_REPORT_TYPE,
    SUMMARY_VERSION as SHADOW_SUMMARY_VERSION,
    validate_shadow_performance_summary,
)


class PromotionReviewReadinessValidationError(ValueError):
    """Raised when PromotionReviewReadinessReport v1 is unsafe or outside FDP-126 bounds."""


REPORT_TYPE = "PROMOTION_REVIEW_READINESS_REPORT_V1"
REPORT_VERSION = "1.0"
GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY"
BANNER = (
    "Promotion review readiness is an offline diagnostic aid only. It is not model promotion approval, "
    "threshold recommendation, production decisioning approval, payment authorization, "
    "automatic approve / decline / block logic, or analyst recommendation logic."
)
READINESS_STATUSES = {"INSUFFICIENT_DATA", "INCONCLUSIVE", "NOT_REVIEWABLE", "REVIEWABLE"}
CHECK_STATUSES = {"PASS", "WARN", "FAIL", "INCONCLUSIVE", "NOT_APPLICABLE"}
SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH"}
CHECK_NAMES = {
    "CURRENT_SUMMARY_PRESENT",
    "CURRENT_SUMMARY_VERSION_SUPPORTED",
    "EVALUATION_CARD_PRESENT",
    "EVALUATION_CARD_VERSION_SUPPORTED",
    "GOVERNANCE_STATUS_DIAGNOSTIC_ONLY",
    "NOT_PRODUCTION_APPROVAL_TRUE",
    "NOT_PROMOTION_APPROVAL_TRUE",
    "NOT_THRESHOLD_RECOMMENDATION_TRUE",
    "NOT_PAYMENT_AUTHORIZATION_TRUE",
    "NOT_AUTOMATIC_DECISIONING_TRUE",
    "EVALUATION_REPORT_TYPE_SUPPORTED",
    "METRIC_BASIS_SUPPORTED",
    "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS",
    "ALERT_RECOMMENDED_PRECISION_AVAILABLE",
    "ALERT_RECOMMENDED_RECALL_AVAILABLE",
    "FALSE_POSITIVE_RATE_AVAILABLE",
    "FALSE_NEGATIVE_RATE_AVAILABLE",
}
REQUIRED_FIELDS = {
    "reportType",
    "reportVersion",
    "generatedAt",
    "governanceStatus",
    "readinessStatus",
    "diagnosticOnly",
    "notPromotionApproval",
    "notThresholdRecommendation",
    "notProductionDecisioning",
    "notPaymentAuthorization",
    "notAutomaticDecisioning",
    "notAnalystRecommendation",
    "inputs",
    "checks",
    "reasonCodes",
    "warnings",
    "limitations",
    "banner",
}
SAFE_NEGATED_FIELDS = {
    "notPromotionApproval",
    "notThresholdRecommendation",
    "notProductionDecisioning",
    "notPaymentAuthorization",
    "notAutomaticDecisioning",
    "notAnalystRecommendation",
}
FORBIDDEN_OUTPUT_TERMS = {
    "approved",
    "promoted",
    "readyforproduction",
    "deploy",
    "deployable",
    "deploymentapproved",
    "changethreshold",
    "recommendedthreshold",
    "thresholdrecommendation",
    "paymentauthorized",
    "autoapprove",
    "autodecline",
    "blocktransaction",
    "analystrecommendation",
    "transactionreference",
    "evaluationrecordid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "rawpayload",
    "rawfeaturevector",
    "rawmlrequest",
    "rawmlresponse",
    "groundtruth",
    "traininglabel",
    "finaldecision",
}
MACHINE_CODE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]{0,127}$")


def build_promotion_review_readiness_report(
        current_summary: dict[str, Any],
        *,
        generated_at: str,
        minimum_diagnostic_evidence_records: int = 1,
) -> dict[str, Any]:
    summary = validate_shadow_performance_summary(current_summary)
    if minimum_diagnostic_evidence_records < 1:
        raise PromotionReviewReadinessValidationError("minimumDiagnosticEvidenceRecords must be positive")

    records_evaluated = summary["evaluationPopulation"]["recordsEvaluated"]
    checks = _checks(summary, minimum_diagnostic_evidence_records)
    failed_checks = [check for check in checks if check["status"] == "FAIL"]
    inconclusive_checks = [check for check in checks if check["status"] == "INCONCLUSIVE"]
    readiness_status = "REVIEWABLE"
    reason_codes: list[str] = []
    if failed_checks:
        readiness_status = "INSUFFICIENT_DATA" if _minimum_evidence_failed(failed_checks) else "NOT_REVIEWABLE"
        reason_codes = [f"{check['name']}_FAILED" for check in failed_checks]
    elif inconclusive_checks:
        readiness_status = "INCONCLUSIVE"
        reason_codes = [f"{check['name']}_INCONCLUSIVE" for check in inconclusive_checks]

    report = {
        "reportType": REPORT_TYPE,
        "reportVersion": REPORT_VERSION,
        "generatedAt": normalize_readiness_timestamp(generated_at, "generatedAt"),
        "governanceStatus": GOVERNANCE_STATUS,
        "readinessStatus": readiness_status,
        "diagnosticOnly": True,
        "notPromotionApproval": True,
        "notThresholdRecommendation": True,
        "notProductionDecisioning": True,
        "notPaymentAuthorization": True,
        "notAutomaticDecisioning": True,
        "notAnalystRecommendation": True,
        "inputs": {
            "shadowPerformanceSummary": {
                "present": True,
                "reportType": summary["reportType"],
                "summaryVersion": summary["summaryVersion"],
                "generatedAt": summary["generatedAt"],
            },
            "minimumDiagnosticEvidenceRecords": minimum_diagnostic_evidence_records,
            "recordsEvaluated": records_evaluated,
        },
        "checks": checks,
        "reasonCodes": reason_codes,
        "warnings": _machine_codes(summary["warnings"]),
        "limitations": [
            "OFFLINE_DIAGNOSTIC_AID_ONLY",
            "HUMAN_REVIEW_START_ONLY",
            "DOES_NOT_RECOMMEND_THRESHOLDS",
            "DOES_NOT_AUTHORIZE_PAYMENTS",
            "DOES_NOT_CHANGE_SCORING",
        ],
        "banner": BANNER,
    }
    return validate_promotion_review_readiness_report(report)


def promotion_review_readiness_report_json(report: dict[str, Any]) -> str:
    safe_report = validate_promotion_review_readiness_report(report)
    payload = json.dumps(safe_report, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_payload(payload)
    return payload + "\n"


def validate_promotion_review_readiness_report(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PromotionReviewReadinessValidationError("promotion review readiness report must be an object")
    _reject_unknown_or_missing(raw, REQUIRED_FIELDS, "report")
    checks = _check_list(raw["checks"])
    normalized = {
        "reportType": _required_constant(raw, "reportType", REPORT_TYPE),
        "reportVersion": _required_constant(raw, "reportVersion", REPORT_VERSION),
        "generatedAt": normalize_readiness_timestamp(raw.get("generatedAt"), "generatedAt"),
        "governanceStatus": _required_constant(raw, "governanceStatus", GOVERNANCE_STATUS),
        "readinessStatus": _readiness_status(raw),
        "diagnosticOnly": _required_true(raw, "diagnosticOnly"),
        "notPromotionApproval": _required_true(raw, "notPromotionApproval"),
        "notThresholdRecommendation": _required_true(raw, "notThresholdRecommendation"),
        "notProductionDecisioning": _required_true(raw, "notProductionDecisioning"),
        "notPaymentAuthorization": _required_true(raw, "notPaymentAuthorization"),
        "notAutomaticDecisioning": _required_true(raw, "notAutomaticDecisioning"),
        "notAnalystRecommendation": _required_true(raw, "notAnalystRecommendation"),
        "inputs": _inputs(raw["inputs"]),
        "checks": checks,
        "reasonCodes": _machine_code_list(raw, "reasonCodes", 20),
        "warnings": _machine_code_list(raw, "warnings", 20),
        "limitations": _machine_code_list(raw, "limitations", 20),
        "banner": _required_constant(raw, "banner", BANNER),
    }
    _validate_status_consistency(normalized)
    _reject_forbidden_payload(json.dumps(normalized, sort_keys=True, separators=(",", ":")))
    return normalized


def _checks(summary: dict[str, Any], minimum_diagnostic_evidence_records: int) -> list[dict[str, str]]:
    governance = summary["governance"]
    evaluation = summary["evaluation"]
    records_evaluated = summary["evaluationPopulation"]["recordsEvaluated"]
    metrics = summary["metrics"]
    return [
        _check("CURRENT_SUMMARY_PRESENT", "PASS"),
        _check("CURRENT_SUMMARY_VERSION_SUPPORTED", "PASS"),
        _check("EVALUATION_CARD_PRESENT", _pass_fail(evaluation["evaluationCardType"] == PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE)),
        _check("EVALUATION_CARD_VERSION_SUPPORTED", _pass_fail(evaluation["evaluationCardVersion"] == PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION)),
        _check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", _pass_fail(governance["governanceStatus"] == GOVERNANCE_STATUS)),
        _check("NOT_PRODUCTION_APPROVAL_TRUE", _pass_fail(governance["notProductionApproval"] is True)),
        _check("NOT_PROMOTION_APPROVAL_TRUE", _pass_fail(governance["notPromotionApproval"] is True)),
        _check("NOT_THRESHOLD_RECOMMENDATION_TRUE", _pass_fail(governance["notThresholdRecommendation"] is True)),
        _check("NOT_PAYMENT_AUTHORIZATION_TRUE", _pass_fail(governance["notPaymentAuthorization"] is True)),
        _check("NOT_AUTOMATIC_DECISIONING_TRUE", _pass_fail(governance["notAutomaticDecisioning"] is True)),
        _check("EVALUATION_REPORT_TYPE_SUPPORTED", _pass_fail(evaluation["evaluationReportType"] == EXPECTED_EVALUATION_REPORT_TYPE)),
        _check("METRIC_BASIS_SUPPORTED", _pass_fail(summary["metricBasis"] == EXPECTED_METRIC_BASIS)),
        _check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", _pass_fail(records_evaluated >= minimum_diagnostic_evidence_records), "HIGH"),
        _metric_availability_check("ALERT_RECOMMENDED_PRECISION_AVAILABLE", metrics["alertRecommendedPrecision"]),
        _metric_availability_check("ALERT_RECOMMENDED_RECALL_AVAILABLE", metrics["alertRecommendedRecall"]),
        _metric_availability_check("FALSE_POSITIVE_RATE_AVAILABLE", metrics["falsePositiveRate"]),
        _metric_availability_check("FALSE_NEGATIVE_RATE_AVAILABLE", metrics["falseNegativeRate"]),
    ]


def _check(name: str, status: str, severity: str = "INFO") -> dict[str, str]:
    return {"name": name, "status": status, "severity": severity}


def _pass_fail(condition: bool) -> str:
    return "PASS" if condition else "FAIL"


def _metric_availability_check(name: str, metric: dict[str, Any]) -> dict[str, str]:
    return _check(name, "PASS" if metric["available"] is True else "INCONCLUSIVE", "MEDIUM")


def _minimum_evidence_failed(checks: list[dict[str, str]]) -> bool:
    return any(check["name"] == "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS" for check in checks)


def _validate_status_consistency(report: dict[str, Any]) -> None:
    failed_checks = [check for check in report["checks"] if check["status"] == "FAIL"]
    inconclusive_checks = [check for check in report["checks"] if check["status"] == "INCONCLUSIVE"]
    if report["readinessStatus"] == "REVIEWABLE" and (failed_checks or inconclusive_checks):
        raise PromotionReviewReadinessValidationError("REVIEWABLE requires all required checks to pass")
    if report["readinessStatus"] == "INCONCLUSIVE" and failed_checks:
        raise PromotionReviewReadinessValidationError("INCONCLUSIVE requires no failed checks")
    if report["readinessStatus"] == GOVERNANCE_STATUS:
        raise PromotionReviewReadinessValidationError("DIAGNOSTIC_ONLY is governanceStatus, not readinessStatus")
    if _timestamp_instant(report["generatedAt"]) < _timestamp_instant(
            report["inputs"]["shadowPerformanceSummary"]["generatedAt"]
    ):
        raise PromotionReviewReadinessValidationError(
            "generatedAt must be greater than or equal to inputs.shadowPerformanceSummary.generatedAt"
        )


def normalize_readiness_timestamp(value: Any, field: str) -> str:
    try:
        return normalize_rfc3339_timestamp(value, field)
    except Fdp123EvaluationCardValidationError as exc:
        raise PromotionReviewReadinessValidationError(str(exc)) from exc


def _inputs(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PromotionReviewReadinessValidationError("inputs must be an object")
    summary = raw.get("shadowPerformanceSummary")
    if not isinstance(summary, dict):
        raise PromotionReviewReadinessValidationError("inputs.shadowPerformanceSummary must be an object")
    minimum = raw.get("minimumDiagnosticEvidenceRecords")
    records_evaluated = raw.get("recordsEvaluated")
    if isinstance(minimum, bool) or not isinstance(minimum, int) or minimum < 1:
        raise PromotionReviewReadinessValidationError("minimumDiagnosticEvidenceRecords must be positive")
    if isinstance(records_evaluated, bool) or not isinstance(records_evaluated, int) or records_evaluated < 0:
        raise PromotionReviewReadinessValidationError("recordsEvaluated must be non-negative")
    normalized_summary = {
        "present": _required_true(summary, "present"),
        "reportType": _required_constant(summary, "reportType", SHADOW_REPORT_TYPE),
        "summaryVersion": _required_constant(summary, "summaryVersion", SHADOW_SUMMARY_VERSION),
        "generatedAt": normalize_readiness_timestamp(
            summary.get("generatedAt"), "inputs.shadowPerformanceSummary.generatedAt"
        ),
    }
    return {
        "shadowPerformanceSummary": normalized_summary,
        "minimumDiagnosticEvidenceRecords": minimum,
        "recordsEvaluated": records_evaluated,
    }


def _check_list(raw: Any) -> list[dict[str, str]]:
    if not isinstance(raw, list) or not raw:
        raise PromotionReviewReadinessValidationError("checks must be a non-empty list")
    checks = []
    for item in raw:
        if not isinstance(item, dict):
            raise PromotionReviewReadinessValidationError("checks must contain objects")
        _reject_unknown_or_missing(item, {"name", "status", "severity"}, "check")
        name = _enum(item, "name", CHECK_NAMES)
        status = _enum(item, "status", CHECK_STATUSES)
        severity = _enum(item, "severity", SEVERITIES)
        checks.append({"name": name, "status": status, "severity": severity})
    return checks


def _required_constant(raw: dict[str, Any], field: str, expected: str) -> str:
    value = _required_string(raw, field, len(expected))
    if value != expected:
        raise PromotionReviewReadinessValidationError(f"{field} must be {expected}")
    return value


def _required_string(raw: dict[str, Any], field: str, max_length: int) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise PromotionReviewReadinessValidationError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise PromotionReviewReadinessValidationError(f"{field} exceeds maximum length")
    return value


def _required_true(raw: dict[str, Any], field: str) -> bool:
    if raw.get(field) is not True:
        raise PromotionReviewReadinessValidationError(f"{field} must be true")
    return True


def _readiness_status(raw: dict[str, Any]) -> str:
    status = _enum(raw, "readinessStatus", READINESS_STATUSES)
    if status == GOVERNANCE_STATUS:
        raise PromotionReviewReadinessValidationError("DIAGNOSTIC_ONLY is governanceStatus, not readinessStatus")
    return status


def _enum(raw: dict[str, Any], field: str, allowed: set[str]) -> str:
    value = _required_string(raw, field, 128)
    if value not in allowed:
        raise PromotionReviewReadinessValidationError(f"{field} has unsupported value")
    return value


def _machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field, [])
    if not isinstance(value, list):
        raise PromotionReviewReadinessValidationError(f"{field} must be a list")
    if len(value) > max_items:
        raise PromotionReviewReadinessValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise PromotionReviewReadinessValidationError(f"{field} must contain machine-code strings")
        result.append(item)
    return sorted(set(result))


def _machine_codes(values: list[str]) -> list[str]:
    result = []
    for value in values:
        if isinstance(value, str) and MACHINE_CODE_PATTERN.fullmatch(value):
            result.append(value)
    return sorted(set(result))


def _reject_unknown_or_missing(raw: dict[str, Any], allowed: set[str], label: str) -> None:
    extra = sorted(set(raw) - allowed)
    if extra:
        raise PromotionReviewReadinessValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise PromotionReviewReadinessValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _reject_forbidden_payload(payload: str) -> None:
    masked = payload.replace(BANNER, "").replace(SHADOW_PERFORMANCE_BANNER, "")
    for field in SAFE_NEGATED_FIELDS:
        masked = masked.replace(field, "")
    for check_name in CHECK_NAMES:
        masked = masked.replace(check_name, "")
        masked = masked.replace(f"{check_name}_FAILED", "")
        masked = masked.replace(f"{check_name}_INCONCLUSIVE", "")
    compact = "".join(character for character in masked.lower() if character.isalnum())
    for term in FORBIDDEN_OUTPUT_TERMS:
        if term in compact:
            raise PromotionReviewReadinessValidationError(f"report contains forbidden term: {term}")
