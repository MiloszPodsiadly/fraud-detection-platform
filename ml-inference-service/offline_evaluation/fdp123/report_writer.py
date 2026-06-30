from __future__ import annotations

import json
from pathlib import Path
from typing import Any


MAX_WARNINGS = 10
REPORT_TYPE = "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
FORBIDDEN_REPORT_COMPACT_TERMS = {
    "rawfeedbackid",
    "feedbackid",
    "rawtransactionid",
    "transactionid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "createdby",
    "submittedby",
    "correlationid",
    "idempotencykey",
    "requestpayloadhash",
    "notes",
    "rawnotes",
    "rawpayload",
    "rawfeaturevector",
    "rawevidence",
    "rawmlrequest",
    "rawmlresponse",
    "endpoint",
    "token",
    "secret",
    "password",
    "stacktrace",
    "exceptionmessage",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentdecision",
    "paymentauthorization",
    "modelpromotion",
    "thresholdrecommendation",
}
ALLOWED_PSEUDONYM_FIELDS = {"evaluationrecordid", "transactionreference"}


def report_json(report: dict[str, Any]) -> str:
    safe_report = dict(report)
    safe_report["reportType"] = REPORT_TYPE
    safe_report["warnings"] = sorted(str(item) for item in safe_report.get("warnings", []))[:MAX_WARNINGS]
    _reject_forbidden_report_fields(safe_report)
    payload = json.dumps(safe_report, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_report_payload(payload)
    return payload + "\n"


def disagreement_jsonl(report: dict[str, Any]) -> str:
    rows = report.get("rows", [])
    if not isinstance(rows, list):
        raise ValueError("disagreement report rows must be a list")
    lines = []
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("disagreement row must be an object")
        _reject_forbidden_report_fields(row)
        payload = json.dumps(row, sort_keys=True, separators=(",", ":"))
        _reject_forbidden_report_payload(payload)
        lines.append(payload)
    return "\n".join(lines) + ("\n" if lines else "")


def write_fdp123_reports(reports: dict[str, Any], output_dir: Path) -> dict[str, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    paths = {
        "evaluationSummary": output_dir / "evaluation_summary.json",
        "scoreBucketReport": output_dir / "score_bucket_report.json",
        "riskLevelReport": output_dir / "risk_level_report.json",
        "disagreementReport": output_dir / "disagreement_report.jsonl",
        "evaluationRunMarkdown": output_dir / "evaluation_run.md",
    }
    paths["evaluationSummary"].write_text(report_json(reports["evaluationSummary"]), encoding="utf-8")
    paths["scoreBucketReport"].write_text(report_json(reports["scoreBucketReport"]), encoding="utf-8")
    paths["riskLevelReport"].write_text(report_json(reports["riskLevelReport"]), encoding="utf-8")
    paths["disagreementReport"].write_text(disagreement_jsonl(reports["disagreementReport"]), encoding="utf-8")
    paths["evaluationRunMarkdown"].write_text(evaluation_run_markdown(reports["evaluationSummary"]), encoding="utf-8")
    return paths


def evaluation_run_markdown(summary: dict[str, Any]) -> str:
    _reject_forbidden_report_fields(summary)
    metrics = summary.get("qualityMetrics", {})
    dataset_summary = metrics.get("datasetSummary", {}) if isinstance(metrics, dict) else {}
    warnings = summary.get("warnings", [])
    lines = [
        "# FDP-123 Offline Evaluation Run",
        "",
        "Status: offline/internal diagnostic artifact.",
        "",
        f"- reportType: {REPORT_TYPE}",
        f"- recordsEvaluated: {dataset_summary.get('recordsEvaluated', 0)}",
        f"- recordsReturned: {dataset_summary.get('recordsReturned', 0)}",
        f"- truncated: {dataset_summary.get('truncated', False)}",
        f"- warnings: {', '.join(warnings) if warnings else 'none'}",
        "",
        "This artifact does not train models, approve deployment, change scoring, change workflow, or authorize payments.",
        "",
    ]
    payload = "\n".join(lines)
    _reject_forbidden_report_payload(payload)
    return payload


def _reject_forbidden_report_fields(value: Any) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            compact_key = _compact(str(key))
            if compact_key in FORBIDDEN_REPORT_COMPACT_TERMS and compact_key not in ALLOWED_PSEUDONYM_FIELDS:
                raise ValueError(f"report contains forbidden field: {key}")
            _reject_forbidden_report_fields(nested)
    elif isinstance(value, list):
        for item in value:
            _reject_forbidden_report_fields(item)


def _reject_forbidden_report_payload(payload: str) -> None:
    scrubbed = payload
    for allowed in ("evaluationRecordId", "transactionReference", "eval_", "txnref_"):
        scrubbed = scrubbed.replace(allowed, "")
    compact_payload = _compact(scrubbed)
    for forbidden in FORBIDDEN_REPORT_COMPACT_TERMS:
        if forbidden in compact_payload:
            raise ValueError(f"report contains forbidden term: {forbidden}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())

