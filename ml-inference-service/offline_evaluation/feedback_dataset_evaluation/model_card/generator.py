from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

from app.models.model_loader import load_validated_model_artifact
from offline_evaluation.feedback_dataset_evaluation.model_card.schema import (
    EVALUATION_ARTIFACT_INTEGRITY_VERIFIED,
    EVALUATION_AVAILABLE,
    EVALUATION_NOT_AVAILABLE,
    EVALUATION_NOT_CONFIGURED,
    MODEL_ARTIFACT_BYTES_VERIFIED,
    MODEL_CARD_SCHEMA_VERSION,
    MODEL_CARD_TYPE,
    NO_AUTHORITY,
    INTENDED_USE,
    NOT_INTENDED_USE,
    PROMOTION_STATE,
    USE_LIMITATIONS,
    VERIFIED_USAGE_MODES,
    ModelCardValidationError,
    validate_model_card,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import ModelEvaluationIdentity
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    read_validated_model_evaluation_artifact_set,
)
from offline_evaluation.feedback_dataset_evaluation.report_contract import (
    MODEL_EVALUATION_ARTIFACT_SET_VERSION,
    MODEL_EVALUATION_REPORT_TYPE,
)


def build_model_card_from_artifacts(
        model_artifact_path: str | Path,
        generated_at: str,
        model_evaluation_dir: str | Path | None = None,
        source_dataset_path: str | Path | None = None,
) -> dict[str, Any]:
    """Build one card from exact model bytes and optional trusted evaluation evidence."""
    model_artifact = load_validated_model_artifact(Path(model_artifact_path))
    identity = model_artifact.artifact_identity
    subject = ModelEvaluationIdentity(
        model_name=identity.model_name,
        model_version=identity.model_version,
        feature_contract_version=identity.feature_contract_version,
        model_artifact_sha256=identity.artifact_sha256,
    ).as_subject()

    if model_evaluation_dir is None:
        if source_dataset_path is not None:
            raise ModelCardValidationError(
                "source dataset verification requires a configured model evaluation artifact set"
            )
        evaluation_evidence = {
            "status": EVALUATION_NOT_AVAILABLE,
            "reason": EVALUATION_NOT_CONFIGURED,
        }
        evaluation_summary = None
    else:
        evidence = read_validated_model_evaluation_artifact_set(
            model_evaluation_dir,
            source_dataset_path=source_dataset_path,
        )
        evaluation_summary = _to_json_value(evidence.summary)
        if evaluation_summary["evaluationSubject"] != subject:
            raise ModelCardValidationError(
                "model evaluation subject does not match the exact model artifact identity"
            )
        evaluation_evidence = {
            "status": EVALUATION_AVAILABLE,
            "reportType": MODEL_EVALUATION_REPORT_TYPE,
            "artifactSetVersion": MODEL_EVALUATION_ARTIFACT_SET_VERSION,
            "reportGeneratedAt": evaluation_summary["generatedAt"],
            "manifestSha256": evidence.manifest_sha256,
            "integrityVerification": EVALUATION_ARTIFACT_INTEGRITY_VERIFIED,
            "sourceDatasetVerificationLevel": evidence.verification_level.value,
        }

    card = {
        "schemaVersion": MODEL_CARD_SCHEMA_VERSION,
        "cardType": MODEL_CARD_TYPE,
        "generatedAt": generated_at,
        "subject": subject,
        "promotionState": PROMOTION_STATE,
        "modelArtifactVerification": {
            "status": MODEL_ARTIFACT_BYTES_VERIFIED,
            "sha256": identity.artifact_sha256,
        },
        "evaluationEvidence": evaluation_evidence,
        "evaluationSummary": evaluation_summary,
        "verifiedUsageBoundary": {
            "allowedModes": list(VERIFIED_USAGE_MODES),
            "intendedUse": list(INTENDED_USE),
            "notIntendedUse": list(NOT_INTENDED_USE),
            "useLimitations": list(USE_LIMITATIONS),
            "runtimeDecisionAuthority": NO_AUTHORITY,
            "promotionAuthority": NO_AUTHORITY,
            "thresholdChangeAuthority": NO_AUTHORITY,
            "paymentAuthorizationAuthority": NO_AUTHORITY,
            "workflowAuthority": NO_AUTHORITY,
        },
    }
    return validate_model_card(card)


def generate_model_card_artifact_set(
        model_artifact_path: str | Path,
        output_dir: str | Path,
        generated_at: str,
        model_evaluation_dir: str | Path | None = None,
        source_dataset_path: str | Path | None = None,
        allow_output_root: str | Path | None = None,
) -> dict[str, Path]:
    """Build and atomically publish the canonical Model Card artifact set."""
    from offline_evaluation.feedback_dataset_evaluation.model_card.writer import write_model_card_artifacts

    card = build_model_card_from_artifacts(
        model_artifact_path=model_artifact_path,
        generated_at=generated_at,
        model_evaluation_dir=model_evaluation_dir,
        source_dataset_path=source_dataset_path,
    )
    return write_model_card_artifacts(
        card,
        Path(output_dir),
        allow_output_root=Path(allow_output_root) if allow_output_root is not None else None,
    )


def _to_json_value(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _to_json_value(nested) for key, nested in value.items()}
    if isinstance(value, tuple):
        return [_to_json_value(item) for item in value]
    return value
