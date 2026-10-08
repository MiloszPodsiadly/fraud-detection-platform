from __future__ import annotations

import re
from dataclasses import dataclass

MODEL_NAME_MAX_LENGTH = 64
MODEL_VERSION_MAX_LENGTH = 64
FEATURE_CONTRACT_VERSION_MAX_LENGTH = 96

IDENTITY_PART_PATTERN = re.compile(r"^[A-Za-z0-9._-]+$")
ARTIFACT_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
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


@dataclass(frozen=True)
class ModelLogicalIdentity:
    """Immutable logical identity of one published model version."""

    model_name: str
    model_version: str

    def __post_init__(self) -> None:
        validate_model_name(self.model_name)
        validate_model_version(self.model_version)


@dataclass(frozen=True)
class ModelArtifactIdentity:
    """Immutable identity of the exact artifact bytes for a logical model version."""

    model_name: str
    model_version: str
    model_type: str
    model_family: str
    feature_contract_version: str
    artifact_sha256: str

    def __post_init__(self) -> None:
        ModelLogicalIdentity(self.model_name, self.model_version)
        _validate_artifact_metadata_part(self.model_type, "modelType")
        _validate_artifact_metadata_part(self.model_family, "modelFamily")
        validate_feature_contract_version(self.feature_contract_version)
        if (
                not isinstance(self.artifact_sha256, str)
                or ARTIFACT_SHA256_PATTERN.fullmatch(self.artifact_sha256) is None
        ):
            raise ValueError("artifactSha256 must be 64 lowercase hexadecimal characters")

    @property
    def logical_identity(self) -> ModelLogicalIdentity:
        return ModelLogicalIdentity(self.model_name, self.model_version)

    def conflicts_with(self, other: ModelArtifactIdentity) -> bool:
        """Return whether the same logical version identifies different immutable artifact state."""
        return self.logical_identity == other.logical_identity and self != other


def validate_model_name(value: str, field_name: str = "modelName") -> str:
    return _validate_identity_part(value, field_name, MODEL_NAME_MAX_LENGTH)


def validate_model_version(value: str, field_name: str = "modelVersion") -> str:
    return _validate_identity_part(value, field_name, MODEL_VERSION_MAX_LENGTH)


def validate_feature_contract_version(value: str, field_name: str = "featureContractVersion") -> str:
    return _validate_identity_part(value, field_name, FEATURE_CONTRACT_VERSION_MAX_LENGTH)


def _validate_artifact_metadata_part(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field_name} must be a bounded non-empty string")
    if len(value) > MODEL_NAME_MAX_LENGTH:
        raise ValueError(f"{field_name} exceeds maximum length of {MODEL_NAME_MAX_LENGTH}")
    if IDENTITY_PART_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{field_name} must match ^[A-Za-z0-9._-]+$")
    return value


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
