from __future__ import annotations

import re

MODEL_NAME_MAX_LENGTH = 64
MODEL_VERSION_MAX_LENGTH = 64
FEATURE_CONTRACT_VERSION_MAX_LENGTH = 96

IDENTITY_PART_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")
FORBIDDEN_MODEL_IDENTITY_COMPACT_TERMS = {
    "accountid",
    "apikey",
    "authorization",
    "bearer",
    "cardid",
    "correlationid",
    "customerid",
    "deviceid",
    "email",
    "endpoint",
    "exceptionmessage",
    "feedbackid",
    "finaldecision",
    "groundtruth",
    "idempotencykey",
    "merchantid",
    "metadata",
    "modeltraininglabel",
    "password",
    "paymentauthorization",
    "rawfeaturevector",
    "rawmlrequest",
    "rawmlresponse",
    "rawpayload",
    "rawrequest",
    "rawresponse",
    "requestpayloadhash",
    "secret",
    "stacktrace",
    "submittedby",
    "token",
    "traininglabel",
    "transactionid",
}


def validate_model_name(value: str, field_name: str = "modelName") -> str:
    return _validate_identity_part(value, field_name, MODEL_NAME_MAX_LENGTH)


def validate_model_version(value: str, field_name: str = "modelVersion") -> str:
    return _validate_identity_part(value, field_name, MODEL_VERSION_MAX_LENGTH)


def validate_feature_contract_version(value: str, field_name: str = "featureContractVersion") -> str:
    return _validate_identity_part(value, field_name, FEATURE_CONTRACT_VERSION_MAX_LENGTH)


def _validate_identity_part(value: str, field_name: str, max_length: int) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field_name} must be a bounded non-empty string")
    if len(value) > max_length:
        raise ValueError(f"{field_name} exceeds maximum length of {max_length}")
    if any(ord(character) < 32 for character in value):
        raise ValueError(f"{field_name} contains control characters")
    if IDENTITY_PART_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{field_name} must match ^[A-Za-z0-9._-]+$")
    compact = "".join(character for character in value.lower() if character.isalnum())
    if any(term in compact for term in FORBIDDEN_MODEL_IDENTITY_COMPACT_TERMS):
        raise ValueError(f"{field_name} contains forbidden value")
    return value
