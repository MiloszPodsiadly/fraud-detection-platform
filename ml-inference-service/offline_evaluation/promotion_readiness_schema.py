from __future__ import annotations

import json
import re
from typing import Any

from offline_evaluation.shadow_performance_schema import (
    ALLOWED_APPROVED_FOR,
    BANNER as SHADOW_PERFORMANCE_BANNER,
    EXPECTED_DATASET_DEDUPLICATION_POLICY,
    EXPECTED_DATASET_TIME_BASIS,
    EXPECTED_EVALUATION_REPORT_TYPE,
    EXPECTED_GOVERNANCE_STATUS,
    EXPECTED_METRIC_BASIS,
    SUMMARY_TYPE as SHADOW_SUMMARY_TYPE,
    SUMMARY_VERSION as SHADOW_SUMMARY_VERSION,
    validate_shadow_performance_summary,
)


class PromotionReadinessValidationError(ValueError):
    """Raised when PromotionReviewReadinessReport v1 is unsafe or outside FDP-111 bounds."""


REPORT_TYPE = "PROMOTION_REVIEW_READINESS_REPORT_V1"
REPORT_VERSION = "1.0"
GOVERNANCE_STATUS = "DIAGNOSTIC_ONLY"
BANNER = (
    "Promotion review readiness is an offline diagnostic aid only. It is not model promotion approval, "
    "threshold recommendation, production decisioning approval, payment authorization, "
    "automatic approve / decline / block logic, or analyst recommendation logic."
)
READINESS_STATUSES = {"INSUFFICIENT_DATA", "NOT_REVIEWABLE", "REVIEWABLE"}
CHECK_STATUSES = {"PASS", "WARN", "FAIL", "NOT_APPLICABLE"}
SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH"}
CHECK_NAMES = {
    "CURRENT_SUMMARY_PRESENT",
    "CURRENT_SUMMARY_VERSION_SUPPORTED",
    "MODEL_CARD_PRESENT",
    "MODEL_CARD_VERSION_SUPPORTED",
    "GOVERNANCE_STATUS_DIAGNOSTIC_ONLY",
    "APPROVED_FOR_COMPARE_AND_SHADOW",
    "NOT_PRODUCTION_APPROVAL_TRUE",
    "NOT_PROMOTION_APPROVAL_TRUE",
    "NOT_THRESHOLD_RECOMMENDATION_TRUE",
    "NOT_PAYMENT_AUTHORIZATION_TRUE",
    "NOT_AUTOMATIC_DECISIONING_TRUE",
    "EVALUATION_REPORT_TYPE_SUPPORTED",
    "METRIC_BASIS_SUPPORTED",
    "DATASET_TIME_BASIS_SUPPORTED",
    "DEDUPLICATION_POLICY_SUPPORTED",
    "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS",
    "METRICS_PRESENT",
    "DISAGREEMENT_SUMMARY_PRESENT",
    "WARNINGS_PRESENT",
    "LIMITATIONS_PRESENT",
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
        raise PromotionReadinessValidationError("minimumDiagnosticEvidenceRecords must be positive")

    accepted_records = summary["evaluationPopulation"]["recordsAcceptedForEvaluation"]
    checks = _checks(summary, minimum_diagnostic_evidence_records)
    failed_checks = [check for check in checks if check["status"] == "FAIL"]
    readiness_status = "REVIEWABLE"
    reason_codes: list[str] = []
    if failed_checks:
        readiness_status = "INSUFFICIENT_DATA" if _minimum_evidence_failed(failed_checks) else "NOT_REVIEWABLE"
        reason_codes = [f"{check['name']}_FAILED" for check in failed_checks]

    report = {
        "reportType": REPORT_TYPE,
        "reportVersion": REPORT_VERSION,
        "generatedAt": generated_at,
        "governanceStatus": GOVERNANCE_STATUS,
        "readinessStatus": readiness_status,
        "diagnosticOnly": True,
        "notPromotionApproval": True,
        "notThresholdRecommendation": True,
        "notProductionDecisioning": True,
        "notPaymentAuthorization": True,
        "notAutomaticDecisioning": True,
        "inputs": {
            "shadowPerformanceSummary": {
                "present": True,
                "summaryType": summary["summaryType"],
                "summaryVersion": summary["summaryVersion"],
                "generatedAt": summary["generatedAt"],
            },
            "minimumDiagnosticEvidenceRecords": minimum_diagnostic_evidence_records,
            "recordsAcceptedForEvaluation": accepted_records,
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
        raise PromotionReadinessValidationError("promotion review readiness report must be an object")
    _reject_unknown_or_missing(raw, REQUIRED_FIELDS, "report")
    checks = _check_list(raw["checks"])
    normalized = {
        "reportType": _required_constant(raw, "reportType", REPORT_TYPE),
        "reportVersion": _required_constant(raw, "reportVersion", REPORT_VERSION),
        "generatedAt": _required_string(raw, "generatedAt", 128),
        "governanceStatus": _required_constant(raw, "governanceStatus", GOVERNANCE_STATUS),
        "readinessStatus": _readiness_status(raw),
        "diagnosticOnly": _required_true(raw, "diagnosticOnly"),
        "notPromotionApproval": _required_true(raw, "notPromotionApproval"),
        "notThresholdRecommendation": _required_true(raw, "notThresholdRecommendation"),
        "notProductionDecisioning": _required_true(raw, "notProductionDecisioning"),
        "notPaymentAuthorization": _required_true(raw, "notPaymentAuthorization"),
        "notAutomaticDecisioning": _required_true(raw, "notAutomaticDecisioning"),
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
    accepted = summary["evaluationPopulation"]["recordsAcceptedForEvaluation"]
    checks = [
        _check("CURRENT_SUMMARY_PRESENT", "PASS"),
        _check("CURRENT_SUMMARY_VERSION_SUPPORTED", "PASS"),
        _check("MODEL_CARD_PRESENT", "NOT_APPLICABLE", "LOW"),
        _check("MODEL_CARD_VERSION_SUPPORTED", "NOT_APPLICABLE", "LOW"),
        _check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", _pass_fail(governance["governanceStatus"] == GOVERNANCE_STATUS)),
        _check("APPROVED_FOR_COMPARE_AND_SHADOW", _pass_fail(set(governance["approvedFor"]) == ALLOWED_APPROVED_FOR)),
        _check("NOT_PRODUCTION_APPROVAL_TRUE", _pass_fail(governance["notProductionApproval"] is True)),
        _check("NOT_PROMOTION_APPROVAL_TRUE", _pass_fail(governance["notPromotionApproval"] is True)),
        _check("NOT_THRESHOLD_RECOMMENDATION_TRUE", _pass_fail(governance["notThresholdRecommendation"] is True)),
        _check("NOT_PAYMENT_AUTHORIZATION_TRUE", _pass_fail(governance["notPaymentAuthorization"] is True)),
        _check("NOT_AUTOMATIC_DECISIONING_TRUE", _pass_fail(governance["notAutomaticDecisioning"] is True)),
        _check("EVALUATION_REPORT_TYPE_SUPPORTED", _pass_fail(evaluation["evaluationReportType"] == EXPECTED_EVALUATION_REPORT_TYPE)),
        _check("METRIC_BASIS_SUPPORTED", _pass_fail(evaluation["metricBasis"] == EXPECTED_METRIC_BASIS)),
        _check("DATASET_TIME_BASIS_SUPPORTED", _pass_fail(evaluation["datasetTimeBasis"] == EXPECTED_DATASET_TIME_BASIS)),
        _check(
            "DEDUPLICATION_POLICY_SUPPORTED",
            _pass_fail(evaluation["datasetDeduplicationPolicy"] == EXPECTED_DATASET_DEDUPLICATION_POLICY),
        ),
        _check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", _pass_fail(accepted >= minimum_diagnostic_evidence_records), "HIGH"),
        _check("METRICS_PRESENT", _pass_fail(bool(summary["metrics"]))),
        _check("DISAGREEMENT_SUMMARY_PRESENT", _pass_fail(bool(summary["disagreementSummary"]))),
        _check("WARNINGS_PRESENT", "PASS"),
        _check("LIMITATIONS_PRESENT", "PASS"),
    ]
    return checks


def _check(name: str, status: str, severity: str = "INFO") -> dict[str, str]:
    return {"name": name, "status": status, "severity": severity}


def _pass_fail(condition: bool) -> str:
    return "PASS" if condition else "FAIL"


def _minimum_evidence_failed(checks: list[dict[str, str]]) -> bool:
    return any(check["name"] == "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS" for check in checks)


def _validate_status_consistency(report: dict[str, Any]) -> None:
    failed_checks = [check for check in report["checks"] if check["status"] == "FAIL"]
    if report["readinessStatus"] == "REVIEWABLE" and failed_checks:
        raise PromotionReadinessValidationError("REVIEWABLE requires all required checks to pass")
    if report["readinessStatus"] == GOVERNANCE_STATUS:
        raise PromotionReadinessValidationError("DIAGNOSTIC_ONLY is governanceStatus, not readinessStatus")


def _inputs(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PromotionReadinessValidationError("inputs must be an object")
    summary = raw.get("shadowPerformanceSummary")
    if not isinstance(summary, dict):
        raise PromotionReadinessValidationError("inputs.shadowPerformanceSummary must be an object")
    minimum = raw.get("minimumDiagnosticEvidenceRecords")
    accepted = raw.get("recordsAcceptedForEvaluation")
    if isinstance(minimum, bool) or not isinstance(minimum, int) or minimum < 1:
        raise PromotionReadinessValidationError("minimumDiagnosticEvidenceRecords must be positive")
    if isinstance(accepted, bool) or not isinstance(accepted, int) or accepted < 0:
        raise PromotionReadinessValidationError("recordsAcceptedForEvaluation must be non-negative")
    normalized_summary = {
        "present": _required_true(summary, "present"),
        "summaryType": _required_constant(summary, "summaryType", SHADOW_SUMMARY_TYPE),
        "summaryVersion": _required_constant(summary, "summaryVersion", SHADOW_SUMMARY_VERSION),
        "generatedAt": _required_string(summary, "generatedAt", 128),
    }
    return {
        "shadowPerformanceSummary": normalized_summary,
        "minimumDiagnosticEvidenceRecords": minimum,
        "recordsAcceptedForEvaluation": accepted,
    }


def _check_list(raw: Any) -> list[dict[str, str]]:
    if not isinstance(raw, list) or not raw:
        raise PromotionReadinessValidationError("checks must be a non-empty list")
    checks = []
    for item in raw:
        if not isinstance(item, dict):
            raise PromotionReadinessValidationError("checks must contain objects")
        _reject_unknown_or_missing(item, {"name", "status", "severity"}, "check")
        name = _enum(item, "name", CHECK_NAMES)
        status = _enum(item, "status", CHECK_STATUSES)
        severity = _enum(item, "severity", SEVERITIES)
        checks.append({"name": name, "status": status, "severity": severity})
    return checks


def _required_constant(raw: dict[str, Any], field: str, expected: str) -> str:
    value = _required_string(raw, field, len(expected))
    if value != expected:
        raise PromotionReadinessValidationError(f"{field} must be {expected}")
    return value


def _required_string(raw: dict[str, Any], field: str, max_length: int) -> str:
    value = raw.get(field)
    if not isinstance(value, str) or not value:
        raise PromotionReadinessValidationError(f"{field} must be a non-empty string")
    if len(value) > max_length:
        raise PromotionReadinessValidationError(f"{field} exceeds maximum length")
    return value


def _required_true(raw: dict[str, Any], field: str) -> bool:
    if raw.get(field) is not True:
        raise PromotionReadinessValidationError(f"{field} must be true")
    return True


def _readiness_status(raw: dict[str, Any]) -> str:
    status = _enum(raw, "readinessStatus", READINESS_STATUSES)
    if status == GOVERNANCE_STATUS:
        raise PromotionReadinessValidationError("DIAGNOSTIC_ONLY is governanceStatus, not readinessStatus")
    return status


def _enum(raw: dict[str, Any], field: str, allowed: set[str]) -> str:
    value = _required_string(raw, field, 128)
    if value not in allowed:
        raise PromotionReadinessValidationError(f"{field} has unsupported value")
    return value


def _machine_code_list(raw: dict[str, Any], field: str, max_items: int) -> list[str]:
    value = raw.get(field, [])
    if not isinstance(value, list):
        raise PromotionReadinessValidationError(f"{field} must be a list")
    if len(value) > max_items:
        raise PromotionReadinessValidationError(f"{field} exceeds maximum item count")
    result = []
    for item in value:
        if not isinstance(item, str) or MACHINE_CODE_PATTERN.fullmatch(item) is None:
            raise PromotionReadinessValidationError(f"{field} must contain machine-code strings")
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
        raise PromotionReadinessValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(raw))
    if missing:
        raise PromotionReadinessValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _reject_forbidden_payload(payload: str) -> None:
    masked = payload.replace(BANNER, "").replace(SHADOW_PERFORMANCE_BANNER, "")
    for field in SAFE_NEGATED_FIELDS:
        masked = masked.replace(field, "")
    for check_name in CHECK_NAMES:
        masked = masked.replace(check_name, "")
        masked = masked.replace(f"{check_name}_FAILED", "")
    compact = "".join(character for character in masked.lower() if character.isalnum())
    for term in FORBIDDEN_OUTPUT_TERMS:
        if term in compact:
            raise PromotionReadinessValidationError(f"report contains forbidden term: {term}")
