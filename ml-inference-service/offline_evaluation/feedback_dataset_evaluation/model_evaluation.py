from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.models import FeedbackDataset, FeedbackDatasetRecord
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import normalize_rfc3339_timestamp
from model_identity_policy import (
    validate_feature_contract_version,
    validate_model_name,
    validate_model_version,
)


MODEL_EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
MODEL_EVALUATION_METRIC_BASIS = "BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_IDENTITY"
MODEL_IDENTITY_COMPLETE = "COMPLETE"
MODEL_LINEAGE_UNAVAILABLE = "MODEL_LINEAGE_UNAVAILABLE"
MODEL_IDENTITY_MISMATCH = "MODEL_IDENTITY_MISMATCH"
MODEL_PREDICTION_SIGNAL_UNAVAILABLE = "MODEL_PREDICTION_SIGNAL_UNAVAILABLE"
INSUFFICIENT_MODEL_LINEAGE_RECORDS = "INSUFFICIENT_MODEL_LINEAGE_RECORDS"
SINGLE_CLASS_MODEL_LINEAGE_RECORDS = "SINGLE_CLASS_MODEL_LINEAGE_RECORDS"
SOURCE_DATASET_VERSION = "feedback-dataset-v1"
EVALUATION_TIME_BASIS = "FEEDBACK_CREATED_AT"
ROOT_FIELDS = {
    "reportType",
    "generatedAt",
    "sourceDatasetVersion",
    "metricBasis",
    "evaluationSubject",
    "evaluationWindow",
    "population",
    "classBalance",
    "lineagePolicy",
    "supportedMetrics",
    "limitations",
    "warnings",
}
EVALUATION_SUBJECT_FIELDS = {
    "subjectType",
    "modelName",
    "modelVersion",
    "featureContractVersion",
    "identityCompleteness",
}
EVALUATION_WINDOW_FIELDS = {"timeBasis", "fromInclusive", "toInclusive"}
POPULATION_FIELDS = {
    "recordsConsidered",
    "recordsEvaluated",
    "recordsExcludedMissingLineage",
    "recordsExcludedIdentityMismatch",
}
CLASS_BALANCE_FIELDS = {"positiveClassCount", "negativeClassCount"}
LINEAGE_POLICY_FIELDS = {
    "policy",
    "unknownLineageBehavior",
    "identityMismatchBehavior",
    "missingLineageReason",
    "identityMismatchReason",
}
SUPPORTED_METRICS_FIELDS = {"classBalance", "mlPredictionMetrics"}
METRIC_AVAILABILITY_FIELDS = {"available", "reason"}
REQUIRED_LIMITATIONS = {
    "ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS",
    "MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION",
    "MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING",
    "MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS",
    "ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS",
}
ALLOWED_WARNINGS = {
    INSUFFICIENT_MODEL_LINEAGE_RECORDS,
    SINGLE_CLASS_MODEL_LINEAGE_RECORDS,
    MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
}


@dataclass(frozen=True)
class ModelEvaluationIdentity:
    model_name: str
    model_version: str
    feature_contract_version: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "model_name", validate_model_name(self.model_name, "modelName"))
        object.__setattr__(self, "model_version", validate_model_version(self.model_version, "modelVersion"))
        object.__setattr__(
            self,
            "feature_contract_version",
            validate_feature_contract_version(self.feature_contract_version, "featureContractVersion"),
        )

    def as_subject(self) -> dict[str, str]:
        return {
            "subjectType": "ML_MODEL",
            "modelName": self.model_name,
            "modelVersion": self.model_version,
            "featureContractVersion": self.feature_contract_version,
            "identityCompleteness": MODEL_IDENTITY_COMPLETE,
        }


def build_model_specific_evaluation_summary(
        dataset: FeedbackDataset,
        requested_identity: ModelEvaluationIdentity,
        generated_at: str,
) -> dict[str, Any]:
    records = list(dataset.records)
    matching = [record for record in records if _matches_identity(record, requested_identity)]
    missing_lineage = [record for record in records if not _has_complete_identity(record)]
    mismatched = [
        record for record in records
        if _has_complete_identity(record) and not _matches_identity(record, requested_identity)
    ]
    positives = [record for record in matching if record.is_positive_class]
    negatives = [record for record in matching if record.is_negative_class]
    warnings = _warnings(matching, positives, negatives)
    summary = {
        "reportType": MODEL_EVALUATION_REPORT_TYPE,
        "generatedAt": generated_at,
        "sourceDatasetVersion": dataset.metadata.dataset_version,
        "metricBasis": MODEL_EVALUATION_METRIC_BASIS,
        "evaluationSubject": requested_identity.as_subject(),
        "evaluationWindow": {
            "timeBasis": dataset.metadata.time_basis,
            "fromInclusive": dataset.metadata.from_inclusive,
            "toInclusive": dataset.metadata.to_inclusive,
        },
        "population": {
            "recordsConsidered": len(records),
            "recordsEvaluated": len(matching),
            "recordsExcludedMissingLineage": len(missing_lineage),
            "recordsExcludedIdentityMismatch": len(mismatched),
        },
        "classBalance": {
            "positiveClassCount": len(positives),
            "negativeClassCount": len(negatives),
        },
        "lineagePolicy": {
            "policy": "REQUESTED_EXACT_IDENTITY",
            "unknownLineageBehavior": "EXCLUDE",
            "identityMismatchBehavior": "EXCLUDE",
            "missingLineageReason": MODEL_LINEAGE_UNAVAILABLE,
            "identityMismatchReason": MODEL_IDENTITY_MISMATCH,
        },
        "supportedMetrics": {
            "classBalance": {
                "available": bool(matching),
                "reason": None if matching else INSUFFICIENT_MODEL_LINEAGE_RECORDS,
            },
            "mlPredictionMetrics": {
                "available": False,
                "reason": MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
            },
        },
        "limitations": sorted(REQUIRED_LIMITATIONS),
        "warnings": warnings,
    }
    validate_model_evaluation_summary(summary)
    return summary


def validate_model_evaluation_summary(summary: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(summary, dict):
        raise ValueError("model evaluation summary must be an object")
    _reject_unknown_or_missing(summary, ROOT_FIELDS, "model evaluation summary")
    if summary.get("reportType") != MODEL_EVALUATION_REPORT_TYPE:
        raise ValueError("model evaluation summary reportType unsupported")
    if summary.get("sourceDatasetVersion") != SOURCE_DATASET_VERSION:
        raise ValueError("model evaluation summary sourceDatasetVersion unsupported")
    if summary.get("metricBasis") != MODEL_EVALUATION_METRIC_BASIS:
        raise ValueError("model evaluation summary metricBasis unsupported")
    if normalize_rfc3339_timestamp(summary.get("generatedAt"), "generatedAt") != summary.get("generatedAt"):
        raise ValueError("model evaluation summary generatedAt must be canonical")
    _validate_subject(summary.get("evaluationSubject"))
    _validate_window(summary.get("evaluationWindow"))
    population = _validate_population(summary.get("population"))
    class_balance = _validate_class_balance(summary.get("classBalance"))
    if population["recordsEvaluated"] != class_balance["positiveClassCount"] + class_balance["negativeClassCount"]:
        raise ValueError("model evaluation class balance must sum to recordsEvaluated")
    if population["recordsConsidered"] != (
            population["recordsEvaluated"]
            + population["recordsExcludedMissingLineage"]
            + population["recordsExcludedIdentityMismatch"]
    ):
        raise ValueError("model evaluation population counts must reconcile")
    _validate_lineage_policy(summary.get("lineagePolicy"))
    _validate_supported_metrics(summary.get("supportedMetrics"), population["recordsEvaluated"])
    _validate_machine_code_set(summary.get("limitations"), REQUIRED_LIMITATIONS, "limitations", exact=True)
    _validate_machine_code_set(summary.get("warnings"), ALLOWED_WARNINGS, "warnings", exact=False)
    return summary


def _validate_subject(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValueError("evaluationSubject must be an object")
    _reject_unknown_or_missing(value, EVALUATION_SUBJECT_FIELDS, "evaluationSubject")
    if value.get("subjectType") != "ML_MODEL":
        raise ValueError("evaluationSubject subjectType unsupported")
    validate_model_name(value.get("modelName"), "modelName")
    validate_model_version(value.get("modelVersion"), "modelVersion")
    validate_feature_contract_version(value.get("featureContractVersion"), "featureContractVersion")
    if value.get("identityCompleteness") != MODEL_IDENTITY_COMPLETE:
        raise ValueError("evaluationSubject identityCompleteness unsupported")


def _validate_window(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValueError("evaluationWindow must be an object")
    _reject_unknown_or_missing(value, EVALUATION_WINDOW_FIELDS, "evaluationWindow")
    if value.get("timeBasis") != EVALUATION_TIME_BASIS:
        raise ValueError("evaluationWindow timeBasis unsupported")
    for field in ("fromInclusive", "toInclusive"):
        timestamp = value.get(field)
        if timestamp is not None and normalize_rfc3339_timestamp(timestamp, field) != timestamp:
            raise ValueError(f"evaluationWindow {field} must be canonical")


def _validate_population(value: Any) -> dict[str, int]:
    if not isinstance(value, dict):
        raise ValueError("population must be an object")
    _reject_unknown_or_missing(value, POPULATION_FIELDS, "population")
    return {field: _non_negative_int(value.get(field), f"population.{field}") for field in POPULATION_FIELDS}


def _validate_class_balance(value: Any) -> dict[str, int]:
    if not isinstance(value, dict):
        raise ValueError("classBalance must be an object")
    _reject_unknown_or_missing(value, CLASS_BALANCE_FIELDS, "classBalance")
    return {field: _non_negative_int(value.get(field), f"classBalance.{field}") for field in CLASS_BALANCE_FIELDS}


def _validate_lineage_policy(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValueError("lineagePolicy must be an object")
    _reject_unknown_or_missing(value, LINEAGE_POLICY_FIELDS, "lineagePolicy")
    expected = {
        "policy": "REQUESTED_EXACT_IDENTITY",
        "unknownLineageBehavior": "EXCLUDE",
        "identityMismatchBehavior": "EXCLUDE",
        "missingLineageReason": MODEL_LINEAGE_UNAVAILABLE,
        "identityMismatchReason": MODEL_IDENTITY_MISMATCH,
    }
    if value != expected:
        raise ValueError("lineagePolicy unsupported")


def _validate_supported_metrics(value: Any, records_evaluated: int) -> None:
    if not isinstance(value, dict):
        raise ValueError("supportedMetrics must be an object")
    _reject_unknown_or_missing(value, SUPPORTED_METRICS_FIELDS, "supportedMetrics")
    class_balance = _validate_metric_availability(value.get("classBalance"), "supportedMetrics.classBalance")
    ml_prediction = _validate_metric_availability(
        value.get("mlPredictionMetrics"),
        "supportedMetrics.mlPredictionMetrics",
    )
    expected_class_reason = None if records_evaluated else INSUFFICIENT_MODEL_LINEAGE_RECORDS
    if class_balance != {"available": bool(records_evaluated), "reason": expected_class_reason}:
        raise ValueError("supportedMetrics.classBalance unsupported")
    if ml_prediction != {"available": False, "reason": MODEL_PREDICTION_SIGNAL_UNAVAILABLE}:
        raise ValueError("supportedMetrics.mlPredictionMetrics unsupported")


def _validate_metric_availability(value: Any, location: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{location} must be an object")
    _reject_unknown_or_missing(value, METRIC_AVAILABILITY_FIELDS, location)
    if not isinstance(value.get("available"), bool):
        raise ValueError(f"{location}.available must be boolean")
    reason = value.get("reason")
    if reason is not None and not isinstance(reason, str):
        raise ValueError(f"{location}.reason must be string or null")
    return {"available": value["available"], "reason": reason}


def _validate_machine_code_set(value: Any, allowed: set[str], location: str, exact: bool) -> None:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError(f"{location} must be a list of strings")
    if len(set(value)) != len(value):
        raise ValueError(f"{location} must not contain duplicates")
    if value != sorted(value):
        raise ValueError(f"{location} must be sorted")
    values = set(value)
    if exact and values != allowed:
        raise ValueError(f"{location} unsupported")
    if not exact and not values.issubset(allowed):
        raise ValueError(f"{location} unsupported")


def _reject_unknown_or_missing(value: dict[str, Any], allowed: set[str], location: str) -> None:
    extra = sorted(set(value) - allowed)
    if extra:
        raise ValueError(f"{location} contains unsupported fields: {', '.join(extra)}")
    missing = sorted(allowed - set(value))
    if missing:
        raise ValueError(f"{location} missing required fields: {', '.join(missing)}")


def _non_negative_int(value: Any, location: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise ValueError(f"{location} must be a non-negative integer")
    return value


def _warnings(
        matching: list[FeedbackDatasetRecord],
        positives: list[FeedbackDatasetRecord],
        negatives: list[FeedbackDatasetRecord],
) -> list[str]:
    warnings = []
    if not matching:
        warnings.append(INSUFFICIENT_MODEL_LINEAGE_RECORDS)
    if matching and (not positives or not negatives):
        warnings.append(SINGLE_CLASS_MODEL_LINEAGE_RECORDS)
    warnings.append(MODEL_PREDICTION_SIGNAL_UNAVAILABLE)
    return sorted(warnings)


def _matches_identity(record: FeedbackDatasetRecord, requested: ModelEvaluationIdentity) -> bool:
    return (
        record.ml_model_name == requested.model_name
        and record.ml_model_version == requested.model_version
        and record.ml_feature_contract_version == requested.feature_contract_version
    )


def _has_complete_identity(record: FeedbackDatasetRecord) -> bool:
    return bool(record.ml_model_name and record.ml_model_version and record.ml_feature_contract_version)
