from __future__ import annotations

import json
from typing import Any

from offline_evaluation.model_card_schema import (
    SAFE_CONTRACT_VALUES,
    ModelCardValidationError,
    validate_model_card,
)


FORBIDDEN_OUTPUT_TERMS = {
    "transactionreference",
    "evaluationrecordid",
    "rawtransactionid",
    "customerid",
    "accountid",
    "cardid",
    "deviceid",
    "merchantid",
    "analystid",
    "submittedby",
    "correlationid",
    "idempotencykey",
    "requesthash",
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
    "truefraud",
    "finaldecision",
    "paymentauthorization",
    "productionapproved",
    "promotionapproved",
    "promotionready",
    "thresholdrecommendation",
    "deployrecommended",
    "bankcertified",
}


def model_card_json(model_card: dict[str, Any]) -> str:
    safe_model_card = validate_model_card(model_card)
    payload = json.dumps(safe_model_card, sort_keys=True, separators=(",", ":"))
    _reject_forbidden_output(payload)
    return payload + "\n"


def _reject_forbidden_output(payload: str) -> None:
    lowered = payload.lower()
    if "eval-" in lowered or "txnref-" in lowered:
        raise ModelCardValidationError("model card contains forbidden pseudonymous identifier prefix")
    masked = payload
    for safe_value in sorted(SAFE_CONTRACT_VALUES, key=len, reverse=True):
        masked = masked.replace(safe_value, "")
    compact_payload = _compact(masked)
    for forbidden in FORBIDDEN_OUTPUT_TERMS:
        if forbidden in compact_payload:
            raise ModelCardValidationError(f"model card contains forbidden term: {forbidden}")


def _compact(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())
