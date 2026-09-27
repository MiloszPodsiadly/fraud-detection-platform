from __future__ import annotations


REPORT_TYPE = "FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
MODEL_EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
ARTIFACT_SET_VERSION = "feedback-dataset-evaluation-report-artifact-set-v1"
MODEL_EVALUATION_ARTIFACT_SET_VERSION = "ml-model-feedback-dataset-evaluation-artifact-set-v1"

LEGACY_READ_ONLY_REPORT_TYPE = "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
LEGACY_READ_ONLY_ARTIFACT_SET_VERSION = "fdp123-report-artifact-set-v1"

PLATFORM_EVALUATION_CURRENT_IDENTITY = (REPORT_TYPE, ARTIFACT_SET_VERSION)
PLATFORM_EVALUATION_LEGACY_READ_ONLY_IDENTITY = (
    LEGACY_READ_ONLY_REPORT_TYPE,
    LEGACY_READ_ONLY_ARTIFACT_SET_VERSION,
)
PLATFORM_EVALUATION_READ_IDENTITIES = frozenset({
    PLATFORM_EVALUATION_CURRENT_IDENTITY,
    PLATFORM_EVALUATION_LEGACY_READ_ONLY_IDENTITY,
})
PLATFORM_EVALUATION_READ_REPORT_TYPES = frozenset({
    REPORT_TYPE,
    LEGACY_READ_ONLY_REPORT_TYPE,
})


def validate_platform_evaluation_artifact_identity(
        report_type: str,
        artifact_set_version: str,
        context: str,
) -> str:
    identity = (report_type, artifact_set_version)
    if identity == PLATFORM_EVALUATION_CURRENT_IDENTITY:
        return "CURRENT"
    if identity == PLATFORM_EVALUATION_LEGACY_READ_ONLY_IDENTITY:
        return "LEGACY_READ_ONLY"
    raise ValueError(f"{context} platform evaluation artifact identity unsupported")


def is_supported_platform_evaluation_report_type(report_type: str) -> bool:
    return report_type in PLATFORM_EVALUATION_READ_REPORT_TYPES
