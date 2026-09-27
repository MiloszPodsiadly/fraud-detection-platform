from __future__ import annotations


REPORT_TYPE = "FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
MODEL_EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
ARTIFACT_SET_VERSION = "feedback-dataset-evaluation-report-artifact-set-v1"
MODEL_EVALUATION_ARTIFACT_SET_VERSION = "ml-model-feedback-dataset-evaluation-artifact-set-v1"

CURRENT_IDENTITY_COMPLETENESS = "NO_MODEL_ARTIFACT_IDENTITY_IN_FEEDBACK_DATASET_SOURCE"

PLATFORM_EVALUATION_IDENTITY = (REPORT_TYPE, ARTIFACT_SET_VERSION)
PLATFORM_EVALUATION_PROVENANCE = (
    REPORT_TYPE,
    ARTIFACT_SET_VERSION,
    CURRENT_IDENTITY_COMPLETENESS,
)


def validate_platform_evaluation_artifact_identity(
        report_type: str,
        artifact_set_version: str,
        context: str,
) -> None:
    identity = (report_type, artifact_set_version)
    if identity != PLATFORM_EVALUATION_IDENTITY:
        raise ValueError(f"{context} platform evaluation artifact identity unsupported")


def is_canonical_platform_evaluation_report_type(report_type: str) -> bool:
    return report_type == REPORT_TYPE


def validate_platform_evaluation_artifact_provenance(
        report_type: str,
        artifact_set_version: str,
        identity_completeness: str,
        context: str,
) -> None:
    provenance = (report_type, artifact_set_version, identity_completeness)
    if provenance != PLATFORM_EVALUATION_PROVENANCE:
        raise ValueError(f"{context} platform evaluation artifact provenance unsupported")
