from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from offline_evaluation.report_schema import MAX_WARNINGS, REPORT_TYPE


FORBIDDEN_REPORT_COMPACT_TERMS = {
    "rawtransactionid",
    "transactionreference",
    "evaluationrecordid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "submittedby",
    "correlationid",
    "idempotencykey",
    "requestpayloadhash",
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
    "finaldecision",
    "paymentauthorization",
    "modelpromotion",
    "thresholdrecommendation",
}


def report_json(report: dict[str, Any]) -> str:
    safe_report = dict(report)
    safe_report["reportType"] = REPORT_TYPE
    safe_report["warnings"] = sorted(str(item) for item in safe_report.get("warnings", []))[:MAX_WARNINGS]
    payload = json.dumps(safe_report, sort_keys=True, separators=(",", ":"))
    if "eval-" in payload.lower() or "txnref-" in payload.lower():
        raise ValueError("report contains forbidden pseudonymous identifier prefix")
    compact_payload = _compact(payload)
    for forbidden in FORBIDDEN_REPORT_COMPACT_TERMS:
        if forbidden in compact_payload:
            raise ValueError(f"report contains forbidden term: {forbidden}")
    return payload + "\n"


def write_report(report: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(report_json(report), encoding="utf-8")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
