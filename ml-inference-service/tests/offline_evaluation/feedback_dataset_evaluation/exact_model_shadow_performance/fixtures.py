from __future__ import annotations

from offline_evaluation.feedback_dataset_evaluation.dataset_reader import read_feedback_dataset_jsonl
from offline_evaluation.feedback_dataset_evaluation.model_evaluation import (
    ModelEvaluationIdentity,
    build_model_specific_evaluation_summary,
)
from offline_evaluation.feedback_dataset_evaluation.model_evaluation_artifact_set import (
    ModelEvaluationArtifactVerificationLevel,
    ValidatedModelEvaluationArtifactSet,
)

from feedback_dataset_evaluation.feedback_dataset_fixtures import GENERATED_AT, jsonl, jsonl_file, record


IDENTITY = ModelEvaluationIdentity(
    "python-logistic-fraud-model",
    "2026-10-15.shadow",
    "feature-contract-v2",
    "a" * 64,
)
OTHER_BYTES_IDENTITY = ModelEvaluationIdentity(
    IDENTITY.model_name,
    IDENTITY.model_version,
    IDENTITY.feature_contract_version,
    "b" * 64,
)
SHADOW_GENERATED_AT = "2026-06-10T00:00:01Z"


def model_record(index: int, identity: ModelEvaluationIdentity = IDENTITY, **overrides):
    return record(
        evaluationRecordId=f"eval_{index:032d}",
        transactionReference=f"txnref_{index:032d}",
        mlModelName=identity.model_name,
        mlModelVersion=identity.model_version,
        mlFeatureContractVersion=identity.feature_contract_version,
        mlModelArtifactSha256=identity.model_artifact_sha256,
        **overrides,
    )


def evaluation_evidence(
        *records,
        identity: ModelEvaluationIdentity = IDENTITY,
        metadata_overrides=None,
        verified_source: bool = True,
) -> ValidatedModelEvaluationArtifactSet:
    with jsonl_file(jsonl(*records, metadata_overrides=metadata_overrides)) as path:
        dataset = read_feedback_dataset_jsonl(path)
    summary = build_model_specific_evaluation_summary(
        dataset,
        identity,
        GENERATED_AT,
        top_k_values=(1, 2, 4),
    )
    level = (
        ModelEvaluationArtifactVerificationLevel.VERIFIED_AGAINST_SOURCE_BYTES
        if verified_source
        else ModelEvaluationArtifactVerificationLevel.CLAIM_ONLY
    )
    return ValidatedModelEvaluationArtifactSet(
        summary=summary,
        manifest_sha256="1" * 64,
        verification_level=level,
    )


def representative_evidence(identity: ModelEvaluationIdentity = IDENTITY):
    return evaluation_evidence(
        model_record(1, identity, mlPredictionRiskLevel="HIGH", rulesRiskLevel="LOW"),
        model_record(
            2, identity,
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
            mlPredictionRiskLevel="LOW",
            mlPredictionScore=0.2,
            rulesRiskLevel="HIGH",
        ),
        model_record(3, identity, mlPredictionRiskLevel="CRITICAL", rulesRiskLevel="HIGH"),
        model_record(
            4, identity,
            feedbackLabel="CONFIRMED_LEGITIMATE",
            evaluationLabel="NEGATIVE_LEGITIMATE",
            mlPredictionRiskLevel="MEDIUM",
            mlPredictionScore=0.4,
            rulesRiskLevel="LOW",
        ),
        identity=identity,
    )


def thaw(value):
    if isinstance(value, dict) or hasattr(value, "items"):
        return {key: thaw(nested) for key, nested in value.items()}
    if isinstance(value, (tuple, list)):
        return [thaw(item) for item in value]
    return value

