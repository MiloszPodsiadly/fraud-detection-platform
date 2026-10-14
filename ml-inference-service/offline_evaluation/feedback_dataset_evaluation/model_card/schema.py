from __future__ import annotations

import copy
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.governance_artifact_safety import (
    GovernanceArtifactSafetyPolicyError,
    reject_unsafe_structure,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    ModelEvaluationIdentity,
    validate_model_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ModelEvaluationArtifactVerificationLevel,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import (
    compare_rfc3339_timestamps,
    normalize_rfc3339_timestamp,
)
from offline_evaluation.json_contract import dumps_strict_json


class ModelCardValidationError(ValueError):
    """Raised when an exact-artifact ML Model Card violates its contract."""


MODEL_CARD_SCHEMA_VERSION = "ml-model-card-v1"
MODEL_CARD_TYPE = "ML_MODEL_CARD_V1"
MODEL_ARTIFACT_BYTES_VERIFIED = "VERIFIED_AGAINST_ARTIFACT_BYTES"
EVALUATION_AVAILABLE = "AVAILABLE"
EVALUATION_NOT_AVAILABLE = "NOT_AVAILABLE"
EVALUATION_NOT_CONFIGURED = "MODEL_EVALUATION_NOT_CONFIGURED"
EVALUATION_ARTIFACT_INTEGRITY_VERIFIED = "VERIFIED_REPORT_AND_MANIFEST"
PROMOTION_STATE = "NOT_EVALUATED_FOR_PROMOTION"
NO_AUTHORITY = "NONE"
MAX_MODEL_CARD_BYTES = 524_288

VERIFIED_USAGE_MODES = ["COMPARE", "OFFLINE_EVALUATION", "SHADOW"]
VERIFIED_USAGE_BOUNDARY = {
    "allowedModes": VERIFIED_USAGE_MODES,
    "runtimeDecisionAuthority": NO_AUTHORITY,
    "promotionAuthority": NO_AUTHORITY,
    "thresholdChangeAuthority": NO_AUTHORITY,
    "paymentAuthorizationAuthority": NO_AUTHORITY,
    "workflowAuthority": NO_AUTHORITY,
}

ROOT_FIELDS = {
    "schemaVersion",
    "cardType",
    "generatedAt",
    "subject",
    "promotionState",
    "modelArtifactVerification",
    "evaluationEvidence",
    "evaluationSummary",
    "verifiedUsageBoundary",
}
SUBJECT_FIELDS = {
    "subjectType",
    "modelName",
    "modelVersion",
    "featureContractVersion",
    "modelArtifactSha256",
    "identityCompleteness",
}
MODEL_ARTIFACT_VERIFICATION_FIELDS = {"status", "sha256"}
AVAILABLE_EVALUATION_EVIDENCE_FIELDS = {
    "status",
    "reportType",
    "artifactSetVersion",
    "reportGeneratedAt",
    "manifestSha256",
    "integrityVerification",
    "sourceDatasetVerificationLevel",
}
UNAVAILABLE_EVALUATION_EVIDENCE_FIELDS = {"status", "reason"}
USAGE_BOUNDARY_FIELDS = set(VERIFIED_USAGE_BOUNDARY)
SOURCE_DATASET_VERIFICATION_LEVELS = {
    level.value for level in ModelEvaluationArtifactVerificationLevel
}


def validate_model_card(raw: dict[str, Any]) -> dict[str, Any]:
    """Validate and return a detached canonical Model Card value.

    Metric values remain owned by the validated model evaluation summary. This
    validator preserves them verbatim and never derives replacement metrics.
    """
    if not isinstance(raw, dict):
        raise ModelCardValidationError("model card must be an object")
    _validate_json_payload(raw)
    _require_exact_fields(raw, ROOT_FIELDS, "model card")

    card = copy.deepcopy(raw)
    _require_constant(card, "schemaVersion", MODEL_CARD_SCHEMA_VERSION)
    _require_constant(card, "cardType", MODEL_CARD_TYPE)
    generated_at = _canonical_timestamp(card.get("generatedAt"), "generatedAt")
    subject = _validate_subject(card.get("subject"))
    _require_constant(card, "promotionState", PROMOTION_STATE)
    _validate_model_artifact_verification(card.get("modelArtifactVerification"), subject)
    evidence = _validate_evaluation_evidence(card.get("evaluationEvidence"))
    _validate_evaluation_summary(card.get("evaluationSummary"), evidence, subject, generated_at)
    _validate_usage_boundary(card.get("verifiedUsageBoundary"))
    _validate_card_envelope_safety(card)
    return card


def _validate_json_payload(value: Any) -> None:
    try:
        serialized = dumps_strict_json(value, separators=(",", ":"), sort_keys=True)
    except (TypeError, ValueError) as exc:
        raise ModelCardValidationError("model card must contain strict finite JSON values") from exc
    if len(serialized.encode("utf-8")) > MAX_MODEL_CARD_BYTES:
        raise ModelCardValidationError("model card exceeds maximum payload size")


def _validate_card_envelope_safety(card: dict[str, Any]) -> None:
    # The summary has its own closed, recursive contract. Excluding it here
    # avoids treating the canonical ranking-policy name as row-level evidence.
    envelope = {key: value for key, value in card.items() if key != "evaluationSummary"}
    try:
        reject_unsafe_structure(envelope)
    except GovernanceArtifactSafetyPolicyError as exc:
        raise ModelCardValidationError(str(exc)) from exc


def _validate_subject(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise ModelCardValidationError("subject must be an object")
    _require_exact_fields(value, SUBJECT_FIELDS, "subject")
    try:
        expected = ModelEvaluationIdentity(
            model_name=value.get("modelName"),
            model_version=value.get("modelVersion"),
            feature_contract_version=value.get("featureContractVersion"),
            model_artifact_sha256=value.get("modelArtifactSha256"),
        ).as_subject()
    except ValueError as exc:
        raise ModelCardValidationError(str(exc)) from exc
    if value != expected:
        raise ModelCardValidationError("subject must use the canonical complete ML model identity")
    return value


def _validate_model_artifact_verification(value: Any, subject: dict[str, str]) -> None:
    if not isinstance(value, dict):
        raise ModelCardValidationError("modelArtifactVerification must be an object")
    _require_exact_fields(value, MODEL_ARTIFACT_VERIFICATION_FIELDS, "modelArtifactVerification")
    if value.get("status") != MODEL_ARTIFACT_BYTES_VERIFIED:
        raise ModelCardValidationError("modelArtifactVerification.status unsupported")
    if value.get("sha256") != subject["modelArtifactSha256"]:
        raise ModelCardValidationError("model artifact byte verification must match the exact subject digest")


def _validate_evaluation_evidence(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ModelCardValidationError("evaluationEvidence must be an object")
    status = value.get("status")
    if status == EVALUATION_NOT_AVAILABLE:
        _require_exact_fields(value, UNAVAILABLE_EVALUATION_EVIDENCE_FIELDS, "evaluationEvidence")
        if value.get("reason") != EVALUATION_NOT_CONFIGURED:
            raise ModelCardValidationError("evaluationEvidence.reason unsupported")
        return value
    if status != EVALUATION_AVAILABLE:
        raise ModelCardValidationError("evaluationEvidence.status unsupported")

    _require_exact_fields(value, AVAILABLE_EVALUATION_EVIDENCE_FIELDS, "evaluationEvidence")
    if value.get("reportType") != MODEL_EVALUATION_REPORT_TYPE:
        raise ModelCardValidationError("evaluationEvidence.reportType unsupported")
    if value.get("artifactSetVersion") != MODEL_EVALUATION_ARTIFACT_SET_VERSION:
        raise ModelCardValidationError("evaluationEvidence.artifactSetVersion unsupported")
    _canonical_timestamp(value.get("reportGeneratedAt"), "evaluationEvidence.reportGeneratedAt")
    _require_lowercase_sha256(value.get("manifestSha256"), "evaluationEvidence.manifestSha256")
    if value.get("integrityVerification") != EVALUATION_ARTIFACT_INTEGRITY_VERIFIED:
        raise ModelCardValidationError("evaluationEvidence.integrityVerification unsupported")
    if value.get("sourceDatasetVerificationLevel") not in SOURCE_DATASET_VERIFICATION_LEVELS:
        raise ModelCardValidationError("evaluationEvidence.sourceDatasetVerificationLevel unsupported")
    return value


def _validate_evaluation_summary(
        value: Any,
        evidence: dict[str, Any],
        subject: dict[str, str],
        generated_at: str,
) -> None:
    if evidence["status"] == EVALUATION_NOT_AVAILABLE:
        if value is not None:
            raise ModelCardValidationError("evaluationSummary must be null when evaluation is not configured")
        return
    if not isinstance(value, dict):
        raise ModelCardValidationError("evaluationSummary must be an object when evaluation is available")
    try:
        validate_model_evaluation_summary(value)
    except ValueError as exc:
        raise ModelCardValidationError(f"evaluationSummary invalid: {exc}") from exc
    if value["evaluationSubject"] != subject:
        raise ModelCardValidationError("evaluationSummary subject must match the exact Model Card subject")
    if value["generatedAt"] != evidence["reportGeneratedAt"]:
        raise ModelCardValidationError("evaluationSummary generatedAt must match evaluation evidence")
    if compare_rfc3339_timestamps(generated_at, value["generatedAt"]) < 0:
        raise ModelCardValidationError("generatedAt must not precede the evaluation report")


def _validate_usage_boundary(value: Any) -> None:
    if not isinstance(value, dict):
        raise ModelCardValidationError("verifiedUsageBoundary must be an object")
    _require_exact_fields(value, USAGE_BOUNDARY_FIELDS, "verifiedUsageBoundary")
    if value != VERIFIED_USAGE_BOUNDARY:
        raise ModelCardValidationError("verifiedUsageBoundary unsupported")


def _require_exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    extra = sorted(set(value) - expected)
    if extra:
        raise ModelCardValidationError(f"{label} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(expected - set(value))
    if missing:
        raise ModelCardValidationError(f"{label} missing required fields: {', '.join(missing)}")


def _require_constant(value: dict[str, Any], field: str, expected: str) -> None:
    if value.get(field) != expected:
        raise ModelCardValidationError(f"{field} unsupported")


def _canonical_timestamp(value: Any, field: str) -> str:
    try:
        normalized = normalize_rfc3339_timestamp(value, field)
    except ValueError as exc:
        raise ModelCardValidationError(str(exc)) from exc
    if normalized != value:
        raise ModelCardValidationError(f"{field} must be canonical")
    return normalized


def _require_lowercase_sha256(value: Any, field: str) -> None:
    malformed = (
        not isinstance(value, str)
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    )
    if malformed:
        raise ModelCardValidationError(f"{field} must be 64 lowercase hexadecimal characters")
