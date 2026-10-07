from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Any

from offline_evaluation.feedback_dataset_evaluation.classification_policy import (
    RISK_CLASSIFICATION_POLICY,
    is_positive_risk,
)
from offline_evaluation.feedback_dataset_evaluation.artifact_integrity import is_lowercase_sha256
from offline_evaluation.feedback_dataset_evaluation.dataset_schema import (
    DATASET_VERSION,
    MAX_DATASET_RECORDS,
    validate_bounded_score,
)
from offline_evaluation.feedback_dataset_evaluation.metrics import (
    DEFAULT_TOP_K_VALUES,
    build_binary_classification_metrics,
    build_binary_classification_metrics_from_counts,
    build_ranked_classification_metrics,
    build_ranked_metric_rows,
)
from offline_evaluation.feedback_dataset_evaluation.models import FeedbackDataset, FeedbackDatasetRecord
from offline_evaluation.feedback_dataset_evaluation.report_contract import MODEL_EVALUATION_REPORT_TYPE
from offline_evaluation.feedback_dataset_evaluation.timestamp_contract import (
    normalize_rfc3339_timestamp,
    validate_optional_timestamp_range,
)
from app.model_identity_policy import (
    validate_feature_contract_version,
    validate_model_name,
    validate_model_version,
)


MODEL_EVALUATION_METRIC_BASIS = "BOUNDED_ANALYST_FEEDBACK_BY_EXACT_ML_MODEL_IDENTITY"
MODEL_IDENTITY_COMPLETE = "COMPLETE"
MODEL_IDENTITY_MISMATCH = "MODEL_IDENTITY_MISMATCH"
MODEL_PREDICTION_SIGNAL_UNAVAILABLE = "MODEL_PREDICTION_SIGNAL_UNAVAILABLE"
RULES_SIGNAL_UNAVAILABLE = "RULES_SIGNAL_UNAVAILABLE"
ML_SCORE_RANKING_POLICY = "EXACT_ML_PREDICTION_SCORE_DESC_EVALUATION_RECORD_ID_ASC_V1"
INSUFFICIENT_MODEL_LINEAGE_RECORDS = "INSUFFICIENT_MODEL_LINEAGE_RECORDS"
SINGLE_CLASS_MODEL_LINEAGE_RECORDS = "SINGLE_CLASS_MODEL_LINEAGE_RECORDS"
UNEXPECTED_ML_EVIDENCE_LOSS = "UNEXPECTED_ML_EVIDENCE_LOSS"
INVALID_ML_EVIDENCE_PRESENT = "INVALID_ML_EVIDENCE_PRESENT"
MODEL_EVALUATION_PARTIAL_COVERAGE = "MODEL_EVALUATION_PARTIAL_COVERAGE"
SOURCE_DATASET_TRUNCATED = "SOURCE_DATASET_TRUNCATED"
SOURCE_DATASET_REQUIRED_FIELDS_MISSING = "SOURCE_DATASET_REQUIRED_FIELDS_MISSING"
SOURCE_DATASET_INVALID_ROWS_SKIPPED = "SOURCE_DATASET_INVALID_ROWS_SKIPPED"
SOURCE_DATASET_VERSION = DATASET_VERSION
EVALUATION_TIME_BASIS = "FEEDBACK_CREATED_AT"
ROOT_FIELDS = {
    "reportType",
    "generatedAt",
    "sourceDataset",
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
    "recordsWithPredictionEvidence",
    "recordsEvaluated",
    "recordsExcludedIdentityMismatch",
    "recordsExcludedSourceIdentityMismatch",
    "recordsExcludedMissingPredictionEvidence",
    "recordsExcludedUnexpectedMissingPredictionEvidence",
    "recordsExcludedInvalidPredictionEvidence",
}
SOURCE_DATASET_FIELDS = {
    "datasetVersion",
    "sha256",
    "rawRowsRead",
    "recordsReturned",
    "excludedUnresolvedCount",
    "excludedGovernanceReviewCount",
    "skippedMissingRequiredFieldCount",
    "skippedInvalidSourceRecordCount",
    "truncated",
}
CLASS_BALANCE_FIELDS = {"positiveClassCount", "negativeClassCount"}
LINEAGE_POLICY_FIELDS = {
    "policy",
    "identityMismatchBehavior",
    "identityMismatchReason",
}
SUPPORTED_METRICS_FIELDS = {"classBalance", "mlPredictionMetrics", "rulesVsMlDisagreement"}
METRIC_AVAILABILITY_FIELDS = {"available", "reason"}
ML_PREDICTION_METRICS_FIELDS = {
    "available",
    "reason",
    "classificationPolicy",
    "rankingPolicy",
    "recordsEvaluated",
    "truePositive",
    "falsePositive",
    "trueNegative",
    "falseNegative",
    "precision",
    "recall",
    "truePositiveRate",
    "falsePositiveRate",
    "falseNegativeRate",
    "precisionAtK",
    "recallAtK",
}
METRIC_VALUE_FIELDS = {"available", "reason", "value"}
RULES_VS_ML_DISAGREEMENT_FIELDS = {
    "available",
    "reason",
    "classificationPolicy",
    "recordsWithExactMlEvidence",
    "recordsCompared",
    "recordsExcludedRulesEvidenceUnavailable",
    "categoryCounts",
}
RULES_VS_ML_CATEGORY_FIELDS = {
    "ML_HIGH_RULES_LOW",
    "RULES_HIGH_ML_LOW",
    "BOTH_HIGH",
    "BOTH_LOW",
}
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
    UNEXPECTED_ML_EVIDENCE_LOSS,
    INVALID_ML_EVIDENCE_PRESENT,
    MODEL_EVALUATION_PARTIAL_COVERAGE,
    SOURCE_DATASET_TRUNCATED,
    SOURCE_DATASET_REQUIRED_FIELDS_MISSING,
    SOURCE_DATASET_INVALID_ROWS_SKIPPED,
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


@dataclass(frozen=True)
class _ModelEvaluationPopulation:
    records_with_prediction_evidence: int
    records_evaluated: list[FeedbackDatasetRecord]
    records_excluded_identity_mismatch: int
    records_excluded_source_identity_mismatch: int
    records_excluded_missing_prediction_evidence: int
    records_excluded_unexpected_missing_prediction_evidence: int
    records_excluded_invalid_prediction_evidence: int


def build_model_specific_evaluation_summary(
        dataset: FeedbackDataset,
        requested_identity: ModelEvaluationIdentity,
        generated_at: str,
        top_k_values: tuple[int, ...] = DEFAULT_TOP_K_VALUES,
) -> dict[str, Any]:
    records = list(dataset.records)
    population = _partition_records(records, requested_identity)
    evaluated = population.records_evaluated
    positives = [record for record in evaluated if record.is_positive_class]
    negatives = [record for record in evaluated if record.is_negative_class]
    prediction_metrics = build_binary_classification_metrics(
        (record.is_positive_class, is_positive_risk(record.ml_prediction_risk_level))
        for record in evaluated
    )
    ranking_metrics = build_ranked_classification_metrics(
        evaluated,
        score_selector=lambda record: record.ml_prediction_score,
        top_k_values=top_k_values,
    )
    prediction_metrics.update({
        "available": bool(evaluated),
        "reason": None if evaluated else MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
        "classificationPolicy": RISK_CLASSIFICATION_POLICY,
        "rankingPolicy": ML_SCORE_RANKING_POLICY,
        **ranking_metrics,
    })
    disagreement = _build_rules_vs_ml_disagreement(evaluated)
    source_dataset = build_source_dataset_identity(dataset)
    warnings = _expected_warnings(
        source_dataset,
        len(records),
        len(evaluated),
        len(positives),
        len(negatives),
        population.records_excluded_unexpected_missing_prediction_evidence,
        population.records_excluded_invalid_prediction_evidence,
    )
    summary = {
        "reportType": MODEL_EVALUATION_REPORT_TYPE,
        "generatedAt": generated_at,
        "sourceDataset": source_dataset,
        "metricBasis": MODEL_EVALUATION_METRIC_BASIS,
        "evaluationSubject": requested_identity.as_subject(),
        "evaluationWindow": {
            "timeBasis": dataset.metadata.time_basis,
            "fromInclusive": dataset.metadata.from_inclusive,
            "toInclusive": dataset.metadata.to_inclusive,
        },
        "population": {
            "recordsConsidered": len(records),
            "recordsWithPredictionEvidence": population.records_with_prediction_evidence,
            "recordsEvaluated": len(evaluated),
            "recordsExcludedIdentityMismatch": population.records_excluded_identity_mismatch,
            "recordsExcludedSourceIdentityMismatch": population.records_excluded_source_identity_mismatch,
            "recordsExcludedMissingPredictionEvidence": population.records_excluded_missing_prediction_evidence,
            "recordsExcludedUnexpectedMissingPredictionEvidence": (
                population.records_excluded_unexpected_missing_prediction_evidence
            ),
            "recordsExcludedInvalidPredictionEvidence": population.records_excluded_invalid_prediction_evidence,
        },
        "classBalance": {
            "positiveClassCount": len(positives),
            "negativeClassCount": len(negatives),
        },
        "lineagePolicy": {
            "policy": "REQUESTED_EXACT_IDENTITY",
            "identityMismatchBehavior": "EXCLUDE",
            "identityMismatchReason": MODEL_IDENTITY_MISMATCH,
        },
        "supportedMetrics": {
            "classBalance": {
                "available": bool(evaluated),
                "reason": None if evaluated else INSUFFICIENT_MODEL_LINEAGE_RECORDS,
            },
            "mlPredictionMetrics": prediction_metrics,
            "rulesVsMlDisagreement": disagreement,
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
    if summary.get("metricBasis") != MODEL_EVALUATION_METRIC_BASIS:
        raise ValueError("model evaluation summary metricBasis unsupported")
    if normalize_rfc3339_timestamp(summary.get("generatedAt"), "generatedAt") != summary.get("generatedAt"):
        raise ValueError("model evaluation summary generatedAt must be canonical")
    _validate_subject(summary.get("evaluationSubject"))
    _validate_window(summary.get("evaluationWindow"))
    source_dataset = _validate_source_dataset(summary.get("sourceDataset"))
    population = _validate_population(summary.get("population"))
    class_balance = _validate_class_balance(summary.get("classBalance"))
    if population["recordsEvaluated"] != class_balance["positiveClassCount"] + class_balance["negativeClassCount"]:
        raise ValueError("model evaluation class balance must sum to recordsEvaluated")
    if population["recordsConsidered"] != (
            population["recordsEvaluated"]
            + population["recordsExcludedIdentityMismatch"]
            + population["recordsExcludedSourceIdentityMismatch"]
            + population["recordsExcludedMissingPredictionEvidence"]
            + population["recordsExcludedUnexpectedMissingPredictionEvidence"]
            + population["recordsExcludedInvalidPredictionEvidence"]
    ):
        raise ValueError("model evaluation population counts must reconcile")
    if population["recordsWithPredictionEvidence"] != (
            population["recordsEvaluated"]
            + population["recordsExcludedIdentityMismatch"]
    ):
        raise ValueError("model evaluation prediction evidence counts must reconcile")
    if population["recordsConsidered"] != source_dataset["recordsReturned"]:
        raise ValueError("model evaluation recordsConsidered must match source dataset recordsReturned")
    _validate_lineage_policy(summary.get("lineagePolicy"))
    _validate_supported_metrics(
        summary.get("supportedMetrics"),
        population["recordsEvaluated"],
        class_balance,
    )
    _validate_machine_code_set(summary.get("limitations"), REQUIRED_LIMITATIONS, "limitations", exact=True)
    _validate_machine_code_set(summary.get("warnings"), ALLOWED_WARNINGS, "warnings", exact=False)
    expected_warnings = _expected_warnings(
        source_dataset,
        population["recordsConsidered"],
        population["recordsEvaluated"],
        class_balance["positiveClassCount"],
        class_balance["negativeClassCount"],
        population["recordsExcludedUnexpectedMissingPredictionEvidence"],
        population["recordsExcludedInvalidPredictionEvidence"],
    )
    if summary["warnings"] != expected_warnings:
        raise ValueError("warnings must match evaluated population and class balance")
    return summary


def build_source_dataset_identity(dataset: FeedbackDataset) -> dict[str, Any]:
    if dataset.metadata.records_returned != len(dataset.records):
        raise ValueError("source dataset recordsReturned must match parsed records")
    source_dataset = {
        "datasetVersion": dataset.metadata.dataset_version,
        "sha256": dataset.source_sha256,
        "rawRowsRead": dataset.metadata.raw_rows_read,
        "recordsReturned": dataset.metadata.records_returned,
        "excludedUnresolvedCount": dataset.metadata.excluded_unresolved_count,
        "excludedGovernanceReviewCount": dataset.metadata.excluded_governance_review_count,
        "skippedMissingRequiredFieldCount": dataset.metadata.skipped_missing_required_field_count,
        "skippedInvalidSourceRecordCount": dataset.metadata.skipped_invalid_source_record_count,
        "truncated": dataset.metadata.truncated,
    }
    _validate_source_dataset(source_dataset)
    return source_dataset


def _validate_source_dataset(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("sourceDataset must be an object")
    _reject_unknown_or_missing(value, SOURCE_DATASET_FIELDS, "sourceDataset")
    if value.get("datasetVersion") != SOURCE_DATASET_VERSION:
        raise ValueError("sourceDataset datasetVersion unsupported")
    if not is_lowercase_sha256(value.get("sha256")):
        raise ValueError("sourceDataset sha256 must be lowercase hex")
    counts = {
        field: _bounded_count(
            value.get(field),
            f"sourceDataset.{field}",
            MAX_DATASET_RECORDS + 1 if field == "rawRowsRead" else MAX_DATASET_RECORDS,
        )
        for field in SOURCE_DATASET_FIELDS
        if field.endswith("Count") or field in {"rawRowsRead", "recordsReturned"}
    }
    truncated = value.get("truncated")
    if not isinstance(truncated, bool):
        raise ValueError("sourceDataset.truncated must be boolean")
    accounted_rows = (
        counts["recordsReturned"]
        + counts["excludedUnresolvedCount"]
        + counts["excludedGovernanceReviewCount"]
        + counts["skippedMissingRequiredFieldCount"]
        + counts["skippedInvalidSourceRecordCount"]
    )
    if counts["rawRowsRead"] != accounted_rows + (1 if truncated else 0):
        raise ValueError("sourceDataset population counts must reconcile")
    return value


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
    extra = sorted(set(value) - EVALUATION_WINDOW_FIELDS)
    if extra:
        raise ValueError(f"evaluationWindow contains unsupported fields: {', '.join(extra)}")
    if "timeBasis" not in value:
        raise ValueError("evaluationWindow missing required fields: timeBasis")
    if value.get("timeBasis") != EVALUATION_TIME_BASIS:
        raise ValueError("evaluationWindow timeBasis unsupported")
    validate_optional_timestamp_range(
        value.get("fromInclusive"),
        value.get("toInclusive"),
        "evaluationWindow.fromInclusive",
        "evaluationWindow.toInclusive",
    )


def _validate_population(value: Any) -> dict[str, int]:
    if not isinstance(value, dict):
        raise ValueError("population must be an object")
    _reject_unknown_or_missing(value, POPULATION_FIELDS, "population")
    return {field: _bounded_count(value.get(field), f"population.{field}") for field in POPULATION_FIELDS}


def _validate_class_balance(value: Any) -> dict[str, int]:
    if not isinstance(value, dict):
        raise ValueError("classBalance must be an object")
    _reject_unknown_or_missing(value, CLASS_BALANCE_FIELDS, "classBalance")
    return {field: _bounded_count(value.get(field), f"classBalance.{field}") for field in CLASS_BALANCE_FIELDS}


def _validate_lineage_policy(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValueError("lineagePolicy must be an object")
    _reject_unknown_or_missing(value, LINEAGE_POLICY_FIELDS, "lineagePolicy")
    expected = {
        "policy": "REQUESTED_EXACT_IDENTITY",
        "identityMismatchBehavior": "EXCLUDE",
        "identityMismatchReason": MODEL_IDENTITY_MISMATCH,
    }
    if value != expected:
        raise ValueError("lineagePolicy unsupported")


def _validate_supported_metrics(
        value: Any,
        records_evaluated: int,
        class_balance: dict[str, int],
) -> None:
    if not isinstance(value, dict):
        raise ValueError("supportedMetrics must be an object")
    _reject_unknown_or_missing(value, SUPPORTED_METRICS_FIELDS, "supportedMetrics")
    class_balance_availability = _validate_metric_availability(
        value.get("classBalance"),
        "supportedMetrics.classBalance",
    )
    expected_class_reason = None if records_evaluated else INSUFFICIENT_MODEL_LINEAGE_RECORDS
    if class_balance_availability != {"available": bool(records_evaluated), "reason": expected_class_reason}:
        raise ValueError("supportedMetrics.classBalance unsupported")
    _validate_ml_prediction_metrics(
        value.get("mlPredictionMetrics"),
        "supportedMetrics.mlPredictionMetrics",
        records_evaluated,
        class_balance,
    )
    _validate_rules_vs_ml_disagreement(
        value.get("rulesVsMlDisagreement"),
        "supportedMetrics.rulesVsMlDisagreement",
        records_evaluated,
    )


def _build_rules_vs_ml_disagreement(records: list[FeedbackDatasetRecord]) -> dict[str, Any]:
    counts = {category: 0 for category in sorted(RULES_VS_ML_CATEGORY_FIELDS)}
    unavailable = 0
    for record in records:
        if record.rules_evidence_status != "AVAILABLE" or record.rules_risk_level is None:
            unavailable += 1
            continue
        ml_high = is_positive_risk(record.ml_prediction_risk_level)
        rules_high = is_positive_risk(record.rules_risk_level)
        if ml_high and not rules_high:
            category = "ML_HIGH_RULES_LOW"
        elif rules_high and not ml_high:
            category = "RULES_HIGH_ML_LOW"
        elif ml_high:
            category = "BOTH_HIGH"
        else:
            category = "BOTH_LOW"
        counts[category] += 1
    compared = len(records) - unavailable
    return {
        "available": compared > 0,
        "reason": None if compared else RULES_SIGNAL_UNAVAILABLE,
        "classificationPolicy": RISK_CLASSIFICATION_POLICY,
        "recordsWithExactMlEvidence": len(records),
        "recordsCompared": compared,
        "recordsExcludedRulesEvidenceUnavailable": unavailable,
        "categoryCounts": counts,
    }


def _validate_rules_vs_ml_disagreement(
        value: Any,
        location: str,
        records_evaluated: int,
) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{location} must be an object")
    _reject_unknown_or_missing(value, RULES_VS_ML_DISAGREEMENT_FIELDS, location)
    if value.get("classificationPolicy") != RISK_CLASSIFICATION_POLICY:
        raise ValueError(f"{location}.classificationPolicy unsupported")
    if not isinstance(value.get("available"), bool):
        raise ValueError(f"{location}.available must be boolean")
    with_exact_ml = _bounded_count(
        value.get("recordsWithExactMlEvidence"),
        f"{location}.recordsWithExactMlEvidence",
    )
    compared = _bounded_count(value.get("recordsCompared"), f"{location}.recordsCompared")
    unavailable = _bounded_count(
        value.get("recordsExcludedRulesEvidenceUnavailable"),
        f"{location}.recordsExcludedRulesEvidenceUnavailable",
    )
    if with_exact_ml != records_evaluated or compared + unavailable != with_exact_ml:
        raise ValueError(f"{location} population counts must reconcile")
    categories = value.get("categoryCounts")
    if not isinstance(categories, dict):
        raise ValueError(f"{location}.categoryCounts must be an object")
    _reject_unknown_or_missing(categories, RULES_VS_ML_CATEGORY_FIELDS, f"{location}.categoryCounts")
    category_total = sum(
        _bounded_count(categories.get(category), f"{location}.categoryCounts.{category}")
        for category in RULES_VS_ML_CATEGORY_FIELDS
    )
    if category_total != compared:
        raise ValueError(f"{location}.categoryCounts must sum to recordsCompared")
    expected_availability = {"available": compared > 0, "reason": None if compared else RULES_SIGNAL_UNAVAILABLE}
    if {"available": value.get("available"), "reason": value.get("reason")} != expected_availability:
        raise ValueError(f"{location} availability is inconsistent")


def _validate_ml_prediction_metrics(
        value: Any,
        location: str,
        records_evaluated: int,
        class_balance: dict[str, int],
) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{location} must be an object")
    _reject_unknown_or_missing(value, ML_PREDICTION_METRICS_FIELDS, location)
    true_positive = _bounded_count(value.get("truePositive"), f"{location}.truePositive")
    false_positive = _bounded_count(value.get("falsePositive"), f"{location}.falsePositive")
    true_negative = _bounded_count(value.get("trueNegative"), f"{location}.trueNegative")
    false_negative = _bounded_count(value.get("falseNegative"), f"{location}.falseNegative")
    metric_records = _bounded_count(value.get("recordsEvaluated"), f"{location}.recordsEvaluated")
    if metric_records != records_evaluated:
        raise ValueError(f"{location}.recordsEvaluated must match population.recordsEvaluated")
    if true_positive + false_negative != class_balance["positiveClassCount"]:
        raise ValueError(f"{location} positive confusion counts must match class balance")
    if true_negative + false_positive != class_balance["negativeClassCount"]:
        raise ValueError(f"{location} negative confusion counts must match class balance")
    for field in ("precision", "recall", "truePositiveRate", "falsePositiveRate", "falseNegativeRate"):
        _validate_metric_value(value.get(field), f"{location}.{field}")
    _validate_ranked_metrics(
        value.get("precisionAtK"),
        value.get("recallAtK"),
        records_evaluated,
        class_balance["positiveClassCount"],
        location,
    )
    expected = build_binary_classification_metrics_from_counts(
        true_positive,
        false_positive,
        true_negative,
        false_negative,
    )
    expected.update({
        "available": bool(records_evaluated),
        "reason": None if records_evaluated else MODEL_PREDICTION_SIGNAL_UNAVAILABLE,
        "classificationPolicy": RISK_CLASSIFICATION_POLICY,
        "rankingPolicy": ML_SCORE_RANKING_POLICY,
        "precisionAtK": value["precisionAtK"],
        "recallAtK": value["recallAtK"],
    })
    if value != expected:
        raise ValueError(f"{location} values are inconsistent")


def _validate_ranked_metrics(
        precision_at_k: Any,
        recall_at_k: Any,
        records_evaluated: int,
        total_positive: int,
        location: str,
) -> None:
    if not isinstance(precision_at_k, dict) or not precision_at_k:
        raise ValueError(f"{location}.precisionAtK must be a non-empty object")
    if not isinstance(recall_at_k, dict) or set(recall_at_k) != set(precision_at_k):
        raise ValueError(f"{location} ranking metric K values must match")
    observed_prefixes: list[tuple[int, int, int]] = []
    for key, precision_row in precision_at_k.items():
        recall_row = recall_at_k[key]
        if not isinstance(precision_row, dict) or not isinstance(recall_row, dict):
            raise ValueError(f"{location} ranking metric rows must be objects")
        row_fields = {"requestedK", "actualK", "value"}
        _reject_unknown_or_missing(precision_row, row_fields, f"{location}.precisionAtK.{key}")
        _reject_unknown_or_missing(recall_row, row_fields, f"{location}.recallAtK.{key}")
        requested_k = precision_row.get("requestedK")
        if (
                not isinstance(requested_k, int)
                or isinstance(requested_k, bool)
                or not 1 <= requested_k <= MAX_DATASET_RECORDS
                or key != str(requested_k)
                or recall_row.get("requestedK") != requested_k
        ):
            raise ValueError(f"{location} ranking requestedK is invalid")
        actual_k = _bounded_count(precision_row.get("actualK"), f"{location}.precisionAtK.{key}.actualK")
        if actual_k != min(requested_k, records_evaluated) or recall_row.get("actualK") != actual_k:
            raise ValueError(f"{location} ranking actualK is inconsistent")
        _validate_metric_value(precision_row.get("value"), f"{location}.precisionAtK.{key}.value")
        _validate_metric_value(recall_row.get("value"), f"{location}.recallAtK.{key}.value")
        matching_positive_counts = [
            positive_at_k
            for positive_at_k in range(min(actual_k, total_positive) + 1)
            if (precision_row, recall_row) == build_ranked_metric_rows(
                positive_at_k,
                total_positive,
                requested_k,
                actual_k,
                records_evaluated > 0,
            )
        ]
        if len(matching_positive_counts) != 1:
            raise ValueError(f"{location} ranking metric values are inconsistent")
        observed_prefixes.append((requested_k, actual_k, matching_positive_counts[0]))
    previous_actual_k = 0
    previous_positive_count = 0
    for _, actual_k, positive_count in sorted(observed_prefixes):
        if (
                actual_k < previous_actual_k
                or positive_count < previous_positive_count
                or positive_count - previous_positive_count > actual_k - previous_actual_k
        ):
            raise ValueError(f"{location} ranking prefixes are inconsistent")
        previous_actual_k = actual_k
        previous_positive_count = positive_count
    if previous_actual_k == records_evaluated and previous_positive_count != total_positive:
        raise ValueError(f"{location} complete ranking must include all positive records")


def _validate_metric_value(value: Any, location: str) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{location} must be an object")
    _reject_unknown_or_missing(value, METRIC_VALUE_FIELDS, location)
    if not isinstance(value.get("available"), bool):
        raise ValueError(f"{location}.available must be boolean")
    reason = value.get("reason")
    metric_value = value.get("value")
    if value["available"]:
        if reason is not None or not isinstance(metric_value, (int, float)) or isinstance(metric_value, bool):
            raise ValueError(f"{location} available metric is malformed")
        if not math.isfinite(float(metric_value)) or not 0.0 <= float(metric_value) <= 1.0:
            raise ValueError(f"{location}.value must be between 0.0 and 1.0")
    elif not isinstance(reason, str) or not reason or metric_value is not None:
        raise ValueError(f"{location} unavailable metric is malformed")


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


def _bounded_count(value: Any, location: str, maximum: int = MAX_DATASET_RECORDS) -> int:
    if (
            not isinstance(value, int)
            or isinstance(value, bool)
            or value < 0
            or value > maximum
    ):
        raise ValueError(f"{location} must be an integer between 0 and {maximum}")
    return value


def _expected_warnings(
        source_dataset: dict[str, Any],
        records_considered: int,
        records_evaluated: int,
        positive_class_count: int,
        negative_class_count: int,
        unexpected_missing_prediction_evidence: int,
        invalid_prediction_evidence: int,
) -> list[str]:
    warnings = []
    if source_dataset["truncated"]:
        warnings.append(SOURCE_DATASET_TRUNCATED)
    if source_dataset["skippedMissingRequiredFieldCount"] > 0:
        warnings.append(SOURCE_DATASET_REQUIRED_FIELDS_MISSING)
    if source_dataset["skippedInvalidSourceRecordCount"] > 0:
        warnings.append(SOURCE_DATASET_INVALID_ROWS_SKIPPED)
    if records_evaluated == 0:
        warnings.extend((INSUFFICIENT_MODEL_LINEAGE_RECORDS, MODEL_PREDICTION_SIGNAL_UNAVAILABLE))
    elif positive_class_count == 0 or negative_class_count == 0:
        warnings.append(SINGLE_CLASS_MODEL_LINEAGE_RECORDS)
    if unexpected_missing_prediction_evidence > 0:
        warnings.append(UNEXPECTED_ML_EVIDENCE_LOSS)
    if invalid_prediction_evidence > 0:
        warnings.append(INVALID_ML_EVIDENCE_PRESENT)
    if records_evaluated < records_considered:
        warnings.append(MODEL_EVALUATION_PARTIAL_COVERAGE)
    return sorted(warnings)


def _partition_records(
        records: list[FeedbackDatasetRecord],
        requested_identity: ModelEvaluationIdentity,
) -> _ModelEvaluationPopulation:
    with_prediction_evidence = 0
    evaluated: list[FeedbackDatasetRecord] = []
    identity_mismatch = 0
    source_identity_mismatch = 0
    missing_prediction_evidence = 0
    unexpected_missing_prediction_evidence = 0
    invalid_prediction_evidence = 0
    for record in records:
        evidence_state = _prediction_evidence_state(record)
        if evidence_state == "LEGITIMATELY_ABSENT":
            missing_prediction_evidence += 1
            continue
        if evidence_state == "MISSING_UNEXPECTEDLY":
            unexpected_missing_prediction_evidence += 1
            continue
        if evidence_state == "IDENTITY_MISMATCH":
            source_identity_mismatch += 1
            continue
        if evidence_state == "INVALID":
            invalid_prediction_evidence += 1
            continue
        identity_state = _model_identity_state(record)
        if identity_state == "INVALID":
            invalid_prediction_evidence += 1
            continue
        with_prediction_evidence += 1
        if not _matches_identity(record, requested_identity):
            identity_mismatch += 1
        else:
            evaluated.append(record)
    return _ModelEvaluationPopulation(
        records_with_prediction_evidence=with_prediction_evidence,
        records_evaluated=evaluated,
        records_excluded_identity_mismatch=identity_mismatch,
        records_excluded_source_identity_mismatch=source_identity_mismatch,
        records_excluded_missing_prediction_evidence=missing_prediction_evidence,
        records_excluded_unexpected_missing_prediction_evidence=unexpected_missing_prediction_evidence,
        records_excluded_invalid_prediction_evidence=invalid_prediction_evidence,
    )


def _prediction_evidence_state(record: FeedbackDatasetRecord) -> str:
    direct_values = (
        record.ml_prediction_score,
        record.ml_prediction_risk_level,
        record.ml_prediction_executed_at,
    )
    identity_values = (
        record.ml_model_name,
        record.ml_model_version,
        record.ml_feature_contract_version,
    )
    if record.ml_prediction_evidence_status in {
        "LEGITIMATELY_ABSENT",
        "MISSING_UNEXPECTEDLY",
        "MALFORMED",
        "IDENTITY_MISMATCH",
    }:
        if any(value is not None for value in direct_values + identity_values):
            return "INVALID"
        return {
            "LEGITIMATELY_ABSENT": "LEGITIMATELY_ABSENT",
            "MISSING_UNEXPECTEDLY": "MISSING_UNEXPECTEDLY",
            "MALFORMED": "INVALID",
            "IDENTITY_MISMATCH": "IDENTITY_MISMATCH",
        }[record.ml_prediction_evidence_status]
    if record.ml_prediction_evidence_status != "AVAILABLE" or any(value is None for value in direct_values):
        return "INVALID"
    try:
        if validate_bounded_score(record.ml_prediction_score, "mlPredictionScore") is None:
            return "INVALID"
        is_positive_risk(record.ml_prediction_risk_level)
        if normalize_rfc3339_timestamp(
                record.ml_prediction_executed_at,
                "mlPredictionExecutedAt",
        ) != record.ml_prediction_executed_at:
            return "INVALID"
    except ValueError:
        return "INVALID"
    return "AVAILABLE"


def _model_identity_state(record: FeedbackDatasetRecord) -> str:
    identity = (
        record.ml_model_name,
        record.ml_model_version,
        record.ml_feature_contract_version,
    )
    present = sum(value is not None for value in identity)
    if present != len(identity):
        return "INVALID"
    try:
        validate_model_name(record.ml_model_name, "mlModelName")
        validate_model_version(record.ml_model_version, "mlModelVersion")
        validate_feature_contract_version(record.ml_feature_contract_version, "mlFeatureContractVersion")
    except ValueError:
        return "INVALID"
    return "COMPLETE"


def _matches_identity(record: FeedbackDatasetRecord, requested: ModelEvaluationIdentity) -> bool:
    return (
        record.ml_model_name == requested.model_name
        and record.ml_model_version == requested.model_version
        and record.ml_feature_contract_version == requested.feature_contract_version
    )
