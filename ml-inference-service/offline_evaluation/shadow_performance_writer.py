from __future__ import annotations

import json
from typing import Any

from offline_evaluation.shadow_performance_schema import (
    BANNER,
    SAFE_CONTRACT_VALUES,
    SAFE_NEGATED_FIELDS,
    ShadowPerformanceValidationError,
    validate_shadow_performance_summary,
)


FORBIDDEN_OUTPUT_TERMS = {
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


def write_shadow_performance_summary(summary: dict[str, Any]) -> str:
    safe_summary = validate_shadow_performance_summary(summary)
    payload = json.dumps(safe_summary, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_output(payload)
    return payload + "\n"


def _reject_forbidden_output(payload: str) -> None:
    lowered = payload.lower()
    if "eval-" in lowered or "txnref-" in lowered:
        raise ShadowPerformanceValidationError("summary contains forbidden pseudonymous identifier prefix")
    masked = payload.replace(BANNER, "")
    for safe_value in sorted(SAFE_CONTRACT_VALUES, key=len, reverse=True):
        masked = masked.replace(safe_value, "")
    for safe_field in SAFE_NEGATED_FIELDS:
        masked = masked.replace(safe_field, "")
    compact_payload = _compact(masked)
    for forbidden in FORBIDDEN_OUTPUT_TERMS:
        if forbidden in compact_payload:
            raise ShadowPerformanceValidationError(f"summary contains forbidden term: {forbidden}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
