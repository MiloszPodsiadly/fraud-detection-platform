from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from offline_evaluation.report_schema import MAX_WARNINGS, REPORT_TYPE


FORBIDDEN_REPORT_TERMS = (
    "rawTransactionId",
    "transactionReference",
    "customerId",
    "accountId",
    "cardId",
    "deviceId",
    "merchantId",
    "analystId",
    "submittedBy",
    "correlationId",
    "idempotencyKey",
    "requestPayloadHash",
    "rawPayload",
    "rawFeatureVector",
    "rawEvidence",
    "rawMlRequest",
    "rawMlResponse",
    "endpoint",
    "token",
    "secret",
    "stacktrace",
    "exceptionMessage",
    "groundTruth",
    "modelTrainingLabel",
    "finalDecision",
    "paymentAuthorization",
    "modelPromotion",
    "thresholdRecommendation",
)


def report_json(report: dict[str, Any]) -> str:
    safe_report = dict(report)
    safe_report["reportType"] = REPORT_TYPE
    safe_report["warnings"] = sorted(str(item) for item in safe_report.get("warnings", []))[:MAX_WARNINGS]
    payload = json.dumps(safe_report, sort_keys=True, separators=(",", ":"))
    for forbidden in FORBIDDEN_REPORT_TERMS:
        if forbidden in payload:
            raise ValueError(f"report contains forbidden term: {forbidden}")
    return payload + "\n"


def write_report(report: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(report_json(report), encoding="utf-8")
