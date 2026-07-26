from __future__ import annotations

import re
from typing import Any

from offline_evaluation.json_contract import JsonContractError, dumps_strict_json, require_finite_number
from offline_evaluation.fdp123.evaluation_card.schema import (
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE,
    PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION,
)
from offline_evaluation.fdp123.timestamp_contract import (
    TimestampContractError,
    normalize_rfc3339_timestamp,
    timestamp_instant,
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
CHECK_STATUSES = {"PASS", "FAIL", "INCONCLUSIVE"}
SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH"}
REQUIRED_CHECK_NAMES = (
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
)
CHECK_NAMES = set(REQUIRED_CHECK_NAMES)
CHECK_SEVERITIES = {
    "CURRENT_SUMMARY_PRESENT": "INFO",
    "CURRENT_SUMMARY_VERSION_SUPPORTED": "INFO",
    "EVALUATION_CARD_PRESENT": "INFO",
    "EVALUATION_CARD_VERSION_SUPPORTED": "INFO",
    "GOVERNANCE_STATUS_DIAGNOSTIC_ONLY": "INFO",
    "NOT_PRODUCTION_APPROVAL_TRUE": "INFO",
    "NOT_PROMOTION_APPROVAL_TRUE": "INFO",
    "NOT_THRESHOLD_RECOMMENDATION_TRUE": "INFO",
    "NOT_PAYMENT_AUTHORIZATION_TRUE": "INFO",
    "NOT_AUTOMATIC_DECISIONING_TRUE": "INFO",
    "EVALUATION_REPORT_TYPE_SUPPORTED": "INFO",
    "METRIC_BASIS_SUPPORTED": "INFO",
    "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS": "HIGH",
    "ALERT_RECOMMENDED_PRECISION_AVAILABLE": "MEDIUM",
    "ALERT_RECOMMENDED_RECALL_AVAILABLE": "MEDIUM",
    "FALSE_POSITIVE_RATE_AVAILABLE": "MEDIUM",
    "FALSE_NEGATIVE_RATE_AVAILABLE": "MEDIUM",
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
    "checkInputs",
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
MAX_DIAGNOSTIC_RECORDS = 1000
REQUIRED_LIMITATIONS = {
    "OFFLINE_DIAGNOSTIC_AID_ONLY",
    "HUMAN_REVIEW_START_ONLY",
    "DOES_NOT_RECOMMEND_THRESHOLDS",
    "DOES_NOT_AUTHORIZE_PAYMENTS",
    "DOES_NOT_CHANGE_SCORING",
}


def build_promotion_review_readiness_report(
        current_summary: dict[str, Any],
        *,
        generated_at: str,
        minimum_diagnostic_evidence_records: int = 1,
        source_shadow_summary_manifest_sha256: str,
) -> dict[str, Any]:
    summary = validate_shadow_performance_summary(current_summary)
    if (
            isinstance(minimum_diagnostic_evidence_records, bool)
            or not isinstance(minimum_diagnostic_evidence_records, int)
            or minimum_diagnostic_evidence_records < 1
            or minimum_diagnostic_evidence_records > MAX_DIAGNOSTIC_RECORDS
    ):
        raise PromotionReviewReadinessValidationError("minimumDiagnosticEvidenceRecords must be in range 1..1000")

    records_evaluated = summary["evaluationPopulation"]["recordsEvaluated"]
    check_inputs = _build_check_inputs(summary, minimum_diagnostic_evidence_records, source_shadow_summary_manifest_sha256)
    checks = _checks_from_inputs(check_inputs)
    readiness_status = _derive_readiness_status(checks)
    reason_codes = _derive_reason_codes(checks)

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
        "checkInputs": check_inputs,
        "checks": checks,
        "reasonCodes": reason_codes,
        "warnings": _machine_codes(summary["warnings"]),
        "limitations": sorted(REQUIRED_LIMITATIONS),
        "banner": BANNER,
    }
    return validate_promotion_review_readiness_report(report)


def promotion_review_readiness_report_json(report: dict[str, Any]) -> str:
    safe_report = validate_promotion_review_readiness_report(report)
    payload = dumps_strict_json(safe_report, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_payload(payload)
    return payload + "\n"


def validate_promotion_review_readiness_report(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PromotionReviewReadinessValidationError("promotion review readiness report must be an object")
    _reject_unknown_or_missing(raw, REQUIRED_FIELDS, "report")
    checks = _check_list(raw["checks"])
    check_inputs = _check_inputs(raw["checkInputs"])
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
        "checkInputs": check_inputs,
        "checks": checks,
        "reasonCodes": _machine_code_list(raw, "reasonCodes", 20),
        "warnings": _machine_code_list(raw, "warnings", 20),
        "limitations": _machine_code_list(raw, "limitations", 20),
        "banner": _required_constant(raw, "banner", BANNER),
    }
    _validate_status_consistency(normalized)
    _reject_forbidden_payload(dumps_strict_json(normalized, sort_keys=True, separators=(",", ":")))
    return normalized


def _build_check_inputs(
        summary: dict[str, Any],
        minimum_diagnostic_evidence_records: int,
        source_shadow_summary_manifest_sha256: str,
) -> dict[str, Any]:
    if not isinstance(source_shadow_summary_manifest_sha256, str) or re.fullmatch(r"[a-f0-9]{64}", source_shadow_summary_manifest_sha256) is None:
        raise PromotionReviewReadinessValidationError("sourceShadowSummaryManifestSha256 must be sha256 hex")
    return {
        "sourceShadowSummaryManifestSha256": source_shadow_summary_manifest_sha256,
        "shadowPerformanceSummary": {
            "present": True,
            "reportType": summary["reportType"],
            "summaryVersion": summary["summaryVersion"],
            "generatedAt": summary["generatedAt"],
            "sourceEvaluationCardManifestSha256": summary["evaluation"]["sourceEvaluationCardManifestSha256"],
        },
        "governance": dict(summary["governance"]),
        "evaluation": {
            "evaluationCardType": summary["evaluation"]["evaluationCardType"],
            "evaluationCardVersion": summary["evaluation"]["evaluationCardVersion"],
            "evaluationReportType": summary["evaluation"]["evaluationReportType"],
        },
        "metricBasis": summary["metricBasis"],
        "minimumDiagnosticEvidenceRecords": minimum_diagnostic_evidence_records,
        "recordsEvaluated": summary["evaluationPopulation"]["recordsEvaluated"],
        "metrics": dict(summary["metrics"]),
    }


def _checks_from_inputs(check_inputs: dict[str, Any]) -> list[dict[str, str]]:
    governance = check_inputs["governance"]
    evaluation = check_inputs["evaluation"]
    records_evaluated = check_inputs["recordsEvaluated"]
    metrics = check_inputs["metrics"]
    return [
        _check("CURRENT_SUMMARY_PRESENT", _pass_fail(check_inputs["shadowPerformanceSummary"]["present"] is True)),
        _check("CURRENT_SUMMARY_VERSION_SUPPORTED", _pass_fail(check_inputs["shadowPerformanceSummary"]["summaryVersion"] == SHADOW_SUMMARY_VERSION)),
        _check("EVALUATION_CARD_PRESENT", _pass_fail(evaluation["evaluationCardType"] == PLATFORM_RECOMMENDATION_EVALUATION_CARD_REPORT_TYPE)),
        _check("EVALUATION_CARD_VERSION_SUPPORTED", _pass_fail(evaluation["evaluationCardVersion"] == PLATFORM_RECOMMENDATION_EVALUATION_CARD_VERSION)),
        _check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY", _pass_fail(governance["governanceStatus"] == GOVERNANCE_STATUS)),
        _check("NOT_PRODUCTION_APPROVAL_TRUE", _pass_fail(governance["notProductionApproval"] is True)),
        _check("NOT_PROMOTION_APPROVAL_TRUE", _pass_fail(governance["notPromotionApproval"] is True)),
        _check("NOT_THRESHOLD_RECOMMENDATION_TRUE", _pass_fail(governance["notThresholdRecommendation"] is True)),
        _check("NOT_PAYMENT_AUTHORIZATION_TRUE", _pass_fail(governance["notPaymentAuthorization"] is True)),
        _check("NOT_AUTOMATIC_DECISIONING_TRUE", _pass_fail(governance["notAutomaticDecisioning"] is True)),
        _check("EVALUATION_REPORT_TYPE_SUPPORTED", _pass_fail(evaluation["evaluationReportType"] == EXPECTED_EVALUATION_REPORT_TYPE)),
        _check("METRIC_BASIS_SUPPORTED", _pass_fail(check_inputs["metricBasis"] == EXPECTED_METRIC_BASIS)),
        _check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", _pass_fail(records_evaluated >= check_inputs["minimumDiagnosticEvidenceRecords"]), "HIGH"),
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
    expected_checks = _checks_from_inputs(report["checkInputs"])
    if report["checks"] != expected_checks:
        raise PromotionReviewReadinessValidationError("checks must match checkInputs")
    for field in ("present", "reportType", "summaryVersion", "generatedAt"):
        if (
                report["inputs"]["shadowPerformanceSummary"][field]
                != report["checkInputs"]["shadowPerformanceSummary"][field]
        ):
            raise PromotionReviewReadinessValidationError("inputs must match checkInputs")
    if report["inputs"]["minimumDiagnosticEvidenceRecords"] != report["checkInputs"]["minimumDiagnosticEvidenceRecords"]:
        raise PromotionReviewReadinessValidationError("inputs must match checkInputs")
    if report["inputs"]["recordsEvaluated"] != report["checkInputs"]["recordsEvaluated"]:
        raise PromotionReviewReadinessValidationError("inputs must match checkInputs")
    expected_status = _derive_readiness_status(report["checks"])
    if report["readinessStatus"] != expected_status:
        raise PromotionReviewReadinessValidationError("readinessStatus must match required checks")
    expected_reason_codes = _derive_reason_codes(report["checks"])
    if report["reasonCodes"] != expected_reason_codes:
        raise PromotionReviewReadinessValidationError("reasonCodes must match required checks")
    if not REQUIRED_LIMITATIONS.issubset(set(report["limitations"])):
        raise PromotionReviewReadinessValidationError("limitations missing diagnostic non-goals")
    if timestamp_instant(report["generatedAt"]) < timestamp_instant(
            report["inputs"]["shadowPerformanceSummary"]["generatedAt"]
    ):
        raise PromotionReviewReadinessValidationError(
            "generatedAt must be greater than or equal to inputs.shadowPerformanceSummary.generatedAt"
        )


def normalize_readiness_timestamp(value: Any, field: str) -> str:
    try:
        return normalize_rfc3339_timestamp(value, field)
    except TimestampContractError as exc:
        raise PromotionReviewReadinessValidationError(str(exc)) from exc


def _inputs(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PromotionReviewReadinessValidationError("inputs must be an object")
    _reject_unknown_or_missing(raw, {"shadowPerformanceSummary", "minimumDiagnosticEvidenceRecords", "recordsEvaluated"}, "inputs")
    summary = raw.get("shadowPerformanceSummary")
    if not isinstance(summary, dict):
        raise PromotionReviewReadinessValidationError("inputs.shadowPerformanceSummary must be an object")
    _reject_unknown_or_missing(summary, {"present", "reportType", "summaryVersion", "generatedAt"}, "inputs.shadowPerformanceSummary")
    minimum = raw.get("minimumDiagnosticEvidenceRecords")
    records_evaluated = raw.get("recordsEvaluated")
    if isinstance(minimum, bool) or not isinstance(minimum, int) or minimum < 1 or minimum > MAX_DIAGNOSTIC_RECORDS:
        raise PromotionReviewReadinessValidationError("minimumDiagnosticEvidenceRecords must be in range 1..1000")
    if (
            isinstance(records_evaluated, bool)
            or not isinstance(records_evaluated, int)
            or records_evaluated < 0
            or records_evaluated > MAX_DIAGNOSTIC_RECORDS
    ):
        raise PromotionReviewReadinessValidationError("recordsEvaluated must be in range 0..1000")
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


def _check_inputs(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise PromotionReviewReadinessValidationError("checkInputs must be an object")
    _reject_unknown_or_missing(raw, {
        "sourceShadowSummaryManifestSha256",
        "shadowPerformanceSummary",
        "governance",
        "evaluation",
        "metricBasis",
        "minimumDiagnosticEvidenceRecords",
        "recordsEvaluated",
        "metrics",
    }, "checkInputs")
    if re.fullmatch(r"[a-f0-9]{64}", _required_string(raw, "sourceShadowSummaryManifestSha256", 64)) is None:
        raise PromotionReviewReadinessValidationError("sourceShadowSummaryManifestSha256 must be sha256 hex")
    summary = raw["shadowPerformanceSummary"]
    if not isinstance(summary, dict):
        raise PromotionReviewReadinessValidationError("checkInputs.shadowPerformanceSummary must be an object")
    _reject_unknown_or_missing(summary, {"present", "reportType", "summaryVersion", "generatedAt", "sourceEvaluationCardManifestSha256"}, "checkInputs.shadowPerformanceSummary")
    governance = raw["governance"]
    if not isinstance(governance, dict):
        raise PromotionReviewReadinessValidationError("checkInputs.governance must be an object")
    _reject_unknown_or_missing(governance, {
        "governanceStatus",
        "diagnosticOnly",
        "notProductionApproval",
        "notPromotionApproval",
        "notThresholdRecommendation",
        "notPaymentAuthorization",
        "notAutomaticDecisioning",
    }, "checkInputs.governance")
    evaluation = raw["evaluation"]
    if not isinstance(evaluation, dict):
        raise PromotionReviewReadinessValidationError("checkInputs.evaluation must be an object")
    _reject_unknown_or_missing(evaluation, {"evaluationCardType", "evaluationCardVersion", "evaluationReportType"}, "checkInputs.evaluation")
    metrics = raw["metrics"]
    if not isinstance(metrics, dict):
        raise PromotionReviewReadinessValidationError("checkInputs.metrics must be an object")
    _reject_unknown_or_missing(metrics, {
        "alertRecommendedPrecision",
        "alertRecommendedRecall",
        "falsePositiveRate",
        "falseNegativeRate",
    }, "checkInputs.metrics")
    minimum = raw["minimumDiagnosticEvidenceRecords"]
    records = raw["recordsEvaluated"]
    if isinstance(minimum, bool) or not isinstance(minimum, int) or minimum < 1 or minimum > MAX_DIAGNOSTIC_RECORDS:
        raise PromotionReviewReadinessValidationError("minimumDiagnosticEvidenceRecords must be in range 1..1000")
    if isinstance(records, bool) or not isinstance(records, int) or records < 0 or records > MAX_DIAGNOSTIC_RECORDS:
        raise PromotionReviewReadinessValidationError("recordsEvaluated must be in range 0..1000")
    return {
        "sourceShadowSummaryManifestSha256": raw["sourceShadowSummaryManifestSha256"],
        "shadowPerformanceSummary": {
            "present": _required_true(summary, "present"),
            "reportType": _required_constant(summary, "reportType", SHADOW_REPORT_TYPE),
            "summaryVersion": _required_constant(summary, "summaryVersion", SHADOW_SUMMARY_VERSION),
            "generatedAt": normalize_readiness_timestamp(summary.get("generatedAt"), "checkInputs.shadowPerformanceSummary.generatedAt"),
            "sourceEvaluationCardManifestSha256": _sha256(summary, "sourceEvaluationCardManifestSha256"),
        },
        "governance": {
            "governanceStatus": _required_constant(governance, "governanceStatus", GOVERNANCE_STATUS),
            "diagnosticOnly": _required_true(governance, "diagnosticOnly"),
            "notProductionApproval": _required_true(governance, "notProductionApproval"),
            "notPromotionApproval": _required_true(governance, "notPromotionApproval"),
            "notThresholdRecommendation": _required_true(governance, "notThresholdRecommendation"),
            "notPaymentAuthorization": _required_true(governance, "notPaymentAuthorization"),
            "notAutomaticDecisioning": _required_true(governance, "notAutomaticDecisioning"),
        },
        "evaluation": {
            "evaluationCardType": _required_string(evaluation, "evaluationCardType", 128),
            "evaluationCardVersion": _required_string(evaluation, "evaluationCardVersion", 128),
            "evaluationReportType": _required_string(evaluation, "evaluationReportType", 128),
        },
        "metricBasis": _required_string(raw, "metricBasis", 128),
        "minimumDiagnosticEvidenceRecords": minimum,
        "recordsEvaluated": records,
        "metrics": {field: _metric_object(metrics[field], f"checkInputs.metrics.{field}") for field in metrics},
    }


def _check_list(raw: Any) -> list[dict[str, str]]:
    if not isinstance(raw, list) or not raw:
        raise PromotionReviewReadinessValidationError("checks must be a non-empty list")
    checks = []
    names = []
    for item in raw:
        if not isinstance(item, dict):
            raise PromotionReviewReadinessValidationError("checks must contain objects")
        _reject_unknown_or_missing(item, {"name", "status", "severity"}, "check")
        name = _enum(item, "name", CHECK_NAMES)
        status = _enum(item, "status", CHECK_STATUSES)
        severity = _enum(item, "severity", SEVERITIES)
        if severity != CHECK_SEVERITIES[name]:
            raise PromotionReviewReadinessValidationError("check.severity does not match required check")
        if status == "INCONCLUSIVE" and not name.endswith("_AVAILABLE"):
            raise PromotionReviewReadinessValidationError("check.status is unsupported for check")
        names.append(name)
        checks.append({"name": name, "status": status, "severity": severity})
    if len(checks) != len(REQUIRED_CHECK_NAMES):
        raise PromotionReviewReadinessValidationError("checks must contain exactly the required checks")
    if len(set(names)) != len(names):
        raise PromotionReviewReadinessValidationError("checks contain duplicate names")
    if set(names) != CHECK_NAMES:
        raise PromotionReviewReadinessValidationError("checks must contain exactly the required checks")
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


def _sha256(raw: dict[str, Any], field: str) -> str:
    value = _required_string(raw, field, 64)
    if re.fullmatch(r"[a-f0-9]{64}", value) is None:
        raise PromotionReviewReadinessValidationError(f"{field} must be sha256 hex")
    return value


def _metric_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PromotionReviewReadinessValidationError(f"{label} must be a metric object")
    _reject_unknown_or_missing(value, {"available", "value", "reason"}, label)
    available = value["available"]
    metric_value = value["value"]
    reason = value["reason"]
    if not isinstance(available, bool):
        raise PromotionReviewReadinessValidationError(f"{label}.available must be boolean")
    if available:
        try:
            metric_value = require_finite_number(metric_value, f"{label}.value")
        except JsonContractError as exc:
            raise PromotionReviewReadinessValidationError(f"{label}.value must be numeric when available") from exc
        if metric_value < 0.0 or metric_value > 1.0:
            raise PromotionReviewReadinessValidationError(f"{label}.value must be in range 0.0..1.0")
        if reason is not None:
            raise PromotionReviewReadinessValidationError(f"{label}.reason must be null when available")
        return {"available": True, "value": metric_value, "reason": None}
    if metric_value is not None:
        raise PromotionReviewReadinessValidationError(f"{label}.value must be null when unavailable")
    if not isinstance(reason, str) or MACHINE_CODE_PATTERN.fullmatch(reason) is None:
        raise PromotionReviewReadinessValidationError(f"{label}.reason must be machine-code when unavailable")
    return {"available": False, "value": None, "reason": reason}


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
    if len(set(result)) != len(result):
        raise PromotionReviewReadinessValidationError(f"{field} contains duplicate values")
    return sorted(result)


def _machine_codes(values: list[str]) -> list[str]:
    result = []
    for value in values:
        if isinstance(value, str) and MACHINE_CODE_PATTERN.fullmatch(value):
            result.append(value)
    if len(set(result)) != len(result):
        raise PromotionReviewReadinessValidationError("machine-code list contains duplicate values")
    return sorted(result)


def _derive_readiness_status(checks: list[dict[str, str]]) -> str:
    failed_checks = [check for check in checks if check["status"] == "FAIL"]
    if any(check["name"] != "MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS" for check in failed_checks):
        return "NOT_REVIEWABLE"
    if _minimum_evidence_failed(failed_checks):
        return "INSUFFICIENT_DATA"
    if any(check["status"] == "INCONCLUSIVE" for check in checks):
        return "INCONCLUSIVE"
    return "REVIEWABLE"


def _derive_reason_codes(checks: list[dict[str, str]]) -> list[str]:
    reason_codes = []
    for check in checks:
        if check["status"] == "FAIL":
            reason_codes.append(f"{check['name']}_FAILED")
        elif check["status"] == "INCONCLUSIVE":
            reason_codes.append(f"{check['name']}_INCONCLUSIVE")
    return sorted(reason_codes)


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
