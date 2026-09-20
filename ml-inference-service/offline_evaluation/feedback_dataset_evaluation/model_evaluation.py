from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.models import FeedbackDataset, FeedbackDatasetRecord


MODEL_EVALUATION_REPORT_TYPE = "ML_MODEL_FEEDBACK_DATASET_EVALUATION_V1"
MODEL_EVALUATION_METRIC_BASIS = "BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_IDENTITY"
MODEL_IDENTITY_COMPLETE = "COMPLETE"
MODEL_LINEAGE_UNAVAILABLE = "MODEL_LINEAGE_UNAVAILABLE"
MODEL_IDENTITY_MISMATCH = "MODEL_IDENTITY_MISMATCH"
MODEL_PREDICTION_SIGNAL_UNAVAILABLE = "MODEL_PREDICTION_SIGNAL_UNAVAILABLE"
INSUFFICIENT_MODEL_LINEAGE_RECORDS = "INSUFFICIENT_MODEL_LINEAGE_RECORDS"
SINGLE_CLASS_MODEL_LINEAGE_RECORDS = "SINGLE_CLASS_MODEL_LINEAGE_RECORDS"
FORBIDDEN_MODEL_IDENTITY_COMPACT_TERMS = {
    "accountid",
    "cardid",
    "customerid",
    "deviceid",
    "email",
    "endpoint",
    "feedbackid",
    "finaldecision",
    "groundtruth",
    "merchantid",
    "modeltraininglabel",
    "password",
    "paymentauthorization",
    "rawfeaturevector",
    "rawmlrequest",
    "rawmlresponse",
    "rawpayload",
    "secret",
    "stacktrace",
    "token",
    "traininglabel",
    "transactionid",
}


@dataclass(frozen=True)
class ModelEvaluationIdentity:
    model_name: str
    model_version: str
    feature_contract_version: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "model_name", _model_identity_part(self.model_name, "modelName"))
        object.__setattr__(self, "model_version", _model_identity_part(self.model_version, "modelVersion"))
        object.__setattr__(
            self,
            "feature_contract_version",
            _model_identity_part(self.feature_contract_version, "featureContractVersion"),
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
    return {
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
        "limitations": [
            "ANALYST_FEEDBACK_LABELS_ARE_REVIEW_SIGNALS",
            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_APPROVE_PROMOTION",
            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_CHANGE_SCORING",
            "MODEL_SPECIFIC_EVALUATION_DOES_NOT_AUTHORIZE_PAYMENTS",
            "ML_PREDICTION_METRICS_REQUIRE_DIRECT_ML_OUTPUT_SIGNALS",
        ],
        "warnings": warnings,
    }


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


def _model_identity_part(value: str, field_name: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 128:
        raise ValueError(f"{field_name} must be a bounded non-empty string")
    if any(ord(character) < 32 for character in value):
        raise ValueError(f"{field_name} contains control characters")
    if "/" in value or "\\" in value or "://" in value or "@" in value or ":" in value:
        raise ValueError(f"{field_name} contains unsafe value")
    compact = "".join(character for character in value.lower() if character.isalnum())
    if any(term in compact for term in FORBIDDEN_MODEL_IDENTITY_COMPACT_TERMS):
        raise ValueError(f"{field_name} contains forbidden value")
    return value
