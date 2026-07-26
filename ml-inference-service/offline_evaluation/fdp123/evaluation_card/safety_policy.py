from __future__ import annotations

from typing import Any


class EvaluationCardSafetyPolicyError(ValueError):
    """Raised when Evaluation Card content contains unsafe fields or policy terms."""


FORBIDDEN_FIELD_NAMES = {
    "transactionid",
    "feedbackid",
    "customerid",
    "correlationid",
    "createdby",
    "evaluationrecordid",
    "transactionreference",
    "notes",
    "rawnotes",
    "rawpayload",
    "rawmlrequest",
    "rawmlresponse",
    "rawfeaturevector",
    "rawevidence",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentdecision",
    "paymentauthorization",
    "promotionrecommended",
    "thresholdrecommendation",
    "productionready",
    "certifiedforproduction",
    "bankcertified",
    "token",
    "secret",
    "password",
}

FORBIDDEN_VALUE_TERMS = FORBIDDEN_FIELD_NAMES | {
    "autodecline",
    "autoapprove",
    "autoblock",
    "champion",
    "productiondecisioning",
    "productionapproved",
    "promotionapproved",
    "promotionready",
}

FORBIDDEN_INPUT_COMPACT_TERMS = FORBIDDEN_FIELD_NAMES | {
    "decisionreasoncodes",
}

FORBIDDEN_OUTPUT_TERMS = FORBIDDEN_INPUT_COMPACT_TERMS | {
    "transactionid",
    "feedbackid",
    "customerid",
    "correlationid",
    "createdby",
    "decisionreasoncodes",
}


def compact_policy_token(value: str) -> str:
    return "".join(character for character in value.lower() if character.isalnum())


def reject_unsafe_structure(
        value: Any,
        *,
        safe_values: set[str] | frozenset[str] = frozenset(),
        forbidden_field_names: set[str] | frozenset[str] = FORBIDDEN_FIELD_NAMES,
        forbidden_value_terms: set[str] | frozenset[str] = FORBIDDEN_VALUE_TERMS,
        field_message: str = "forbidden field",
        value_message: str = "forbidden value",
) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            compact_key = compact_policy_token(str(key))
            if compact_key in forbidden_field_names:
                raise EvaluationCardSafetyPolicyError(f"{field_message}: {key}")
            reject_unsafe_structure(
                nested,
                safe_values=safe_values,
                forbidden_field_names=forbidden_field_names,
                forbidden_value_terms=forbidden_value_terms,
                field_message=field_message,
                value_message=value_message,
            )
    elif isinstance(value, list):
        for item in value:
            reject_unsafe_structure(
                item,
                safe_values=safe_values,
                forbidden_field_names=forbidden_field_names,
                forbidden_value_terms=forbidden_value_terms,
                field_message=field_message,
                value_message=value_message,
            )
    elif isinstance(value, str):
        reject_unsafe_policy_value(value, safe_values=safe_values, forbidden_terms=forbidden_value_terms, message=value_message)


def reject_unsafe_policy_value(
        value: str,
        *,
        safe_values: set[str] | frozenset[str] = frozenset(),
        forbidden_terms: set[str] | frozenset[str] = FORBIDDEN_VALUE_TERMS,
        message: str = "forbidden value",
) -> None:
    if value in safe_values:
        return
    lowered = value.lower()
    if "eval_" in lowered or "txnref_" in lowered or "eval-" in lowered or "txnref-" in lowered:
        raise EvaluationCardSafetyPolicyError("forbidden pseudonymous identifier prefix")
    compact = compact_policy_token(value)
    for term in forbidden_terms:
        if term in compact:
            raise EvaluationCardSafetyPolicyError(f"{message}: {value}")


def reject_unsafe_serialized_payload(
        payload: str,
        *,
        safe_values: set[str] | frozenset[str] = frozenset(),
        safe_fields: tuple[str, ...] = (),
        forbidden_terms: set[str] | frozenset[str] = FORBIDDEN_OUTPUT_TERMS,
) -> None:
    lowered = payload.lower()
    if "eval_" in lowered or "txnref_" in lowered or "eval-" in lowered or "txnref-" in lowered:
        raise EvaluationCardSafetyPolicyError("evaluation card contains forbidden pseudonymous identifier prefix")
    masked = payload
    for safe_value in sorted(safe_values, key=len, reverse=True):
        masked = masked.replace(safe_value, "")
    for safe_field in safe_fields:
        masked = masked.replace(safe_field, "")
    compact_payload = compact_policy_token(masked)
    for forbidden in forbidden_terms:
        if forbidden in compact_payload:
            raise EvaluationCardSafetyPolicyError(f"evaluation card contains forbidden term: {forbidden}")
