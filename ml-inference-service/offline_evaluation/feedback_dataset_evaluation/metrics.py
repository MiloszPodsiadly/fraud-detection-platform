from __future__ import annotations

from collections import Counter
import math
from typing import Callable, Iterable

from offline_evaluation.feedback_dataset_evaluation.dataset_schema import MAX_DATASET_RECORDS
from offline_evaluation.feedback_dataset_evaluation.evaluation_contract import METRIC_BASIS
from offline_evaluation.feedback_dataset_evaluation.models import FeedbackDataset, FeedbackDatasetRecord


DEFAULT_TOP_K_VALUES = (10, 25, 50, 100)
DEFAULT_SCORE_BUCKETS = (0.0, 0.2, 0.4, 0.6, 0.8, 1.0)
DEFAULT_MIN_SAMPLE_SIZE_WARNING_THRESHOLD = 30


def build_feedback_dataset_metrics(
        dataset: FeedbackDataset,
        top_k_values: Iterable[int] = DEFAULT_TOP_K_VALUES,
        score_buckets: Iterable[float] = DEFAULT_SCORE_BUCKETS,
        min_sample_size_warning_threshold: int = DEFAULT_MIN_SAMPLE_SIZE_WARNING_THRESHOLD,
) -> dict[str, object]:
    records = list(dataset.records)
    positives = [record for record in records if record.is_positive_class]
    negatives = [record for record in records if record.is_negative_class]
    ranked_metrics = build_ranked_classification_metrics(
        records,
        score_selector=lambda record: record.fraud_score,
        top_k_values=top_k_values,
    )
    warnings = _warnings(dataset, records, positives, negatives, min_sample_size_warning_threshold)
    return {
        "metricBasis": METRIC_BASIS,
        "datasetSummary": {
            "datasetVersion": dataset.metadata.dataset_version,
            "recordsReturned": dataset.metadata.records_returned,
            "recordsEvaluated": len(records),
            "rawRowsRead": dataset.metadata.raw_rows_read,
            "truncated": dataset.metadata.truncated,
            "excludedUnresolvedCount": dataset.metadata.excluded_unresolved_count,
            "excludedGovernanceReviewCount": dataset.metadata.excluded_governance_review_count,
            "skippedMissingRequiredFieldCount": dataset.metadata.skipped_missing_required_field_count,
            "skippedInvalidSourceRecordCount": dataset.metadata.skipped_invalid_source_record_count,
        },
        "classBalance": {
            "positiveClassCount": len(positives),
            "negativeClassCount": len(negatives),
            "positiveClassShare": _metric_value(len(positives), len(records), "EMPTY_DATASET"),
            "negativeClassShare": _metric_value(len(negatives), len(records), "EMPTY_DATASET"),
        },
        "alertRecommendedConfusionMatrix": _alert_recommended_confusion_matrix(records),
        "riskLevelBreakdown": _risk_level_breakdown(records),
        "fraudScoreBucketAnalysis": _fraud_score_bucket_analysis(records, tuple(score_buckets)),
        "precisionAtK": ranked_metrics["precisionAtK"],
        "recallAtK": ranked_metrics["recallAtK"],
        "missingFraudScoreCount": sum(1 for record in records if record.fraud_score is None),
        "missingAlertRecommendedCount": sum(1 for record in records if record.alert_recommended is None),
        "missingRiskLevelCount": sum(1 for record in records if record.risk_level is None),
        "warnings": warnings,
    }


def _warnings(
        dataset: FeedbackDataset,
        records: list[FeedbackDatasetRecord],
        positives: list[FeedbackDatasetRecord],
        negatives: list[FeedbackDatasetRecord],
        min_sample_size_warning_threshold: int,
) -> list[str]:
    warnings = []
    if not records:
        warnings.append("EMPTY_DATASET")
    if records and (not positives or not negatives):
        warnings.append("SINGLE_CLASS_DATASET")
    if len(records) < min_sample_size_warning_threshold:
        warnings.append("LOW_SAMPLE_SIZE")
    if dataset.metadata.truncated:
        warnings.append("TRUNCATED_DATA")
    if any(record.fraud_score is None for record in records):
        warnings.append("MISSING_SCORE_VALUES")
    if any(record.alert_recommended is None for record in records):
        warnings.append("MISSING_ALERT_RECOMMENDATION_VALUES")
    if any(record.risk_level is None for record in records):
        warnings.append("MISSING_RISK_LEVEL_VALUES")
    return sorted(warnings)


def _alert_recommended_confusion_matrix(records: list[FeedbackDatasetRecord]) -> dict[str, object]:
    evaluated = [record for record in records if record.alert_recommended is not None]
    metrics = build_binary_classification_metrics(
        (record.is_positive_class, bool(record.alert_recommended))
        for record in evaluated
    )
    return {
        "recordsWithSignal": metrics["recordsEvaluated"],
        "truePositive": metrics["truePositive"],
        "falsePositive": metrics["falsePositive"],
        "trueNegative": metrics["trueNegative"],
        "falseNegative": metrics["falseNegative"],
        "missingAlertRecommendedCount": sum(1 for record in records if record.alert_recommended is None),
        "precision": metrics["precision"],
        "recall": metrics["recall"],
        "falsePositiveRate": metrics["falsePositiveRate"],
        "falseNegativeRate": metrics["falseNegativeRate"],
    }


def build_binary_classification_metrics(
        outcomes: Iterable[tuple[bool, bool]],
) -> dict[str, object]:
    evaluated = tuple(outcomes)
    true_positive = sum(1 for actual, predicted in evaluated if actual and predicted)
    false_positive = sum(1 for actual, predicted in evaluated if not actual and predicted)
    true_negative = sum(1 for actual, predicted in evaluated if not actual and not predicted)
    false_negative = sum(1 for actual, predicted in evaluated if actual and not predicted)
    return build_binary_classification_metrics_from_counts(
        true_positive,
        false_positive,
        true_negative,
        false_negative,
    )


def build_binary_classification_metrics_from_counts(
        true_positive: int,
        false_positive: int,
        true_negative: int,
        false_negative: int,
) -> dict[str, object]:
    return {
        "recordsEvaluated": true_positive + false_positive + true_negative + false_negative,
        "truePositive": true_positive,
        "falsePositive": false_positive,
        "trueNegative": true_negative,
        "falseNegative": false_negative,
        "precision": _metric_value(true_positive, true_positive + false_positive, "NO_PREDICTED_POSITIVES"),
        "recall": _metric_value(true_positive, true_positive + false_negative, "NO_ACTUAL_POSITIVES"),
        "truePositiveRate": _metric_value(true_positive, true_positive + false_negative, "NO_ACTUAL_POSITIVES"),
        "falsePositiveRate": _metric_value(false_positive, false_positive + true_negative, "NO_ACTUAL_NEGATIVES"),
        "falseNegativeRate": _metric_value(false_negative, false_negative + true_positive, "NO_ACTUAL_POSITIVES"),
    }


def _risk_level_breakdown(records: list[FeedbackDatasetRecord]) -> dict[str, dict[str, int]]:
    buckets: dict[str, Counter[str]] = {}
    for record in records:
        bucket = str(record.risk_level or "MISSING")
        if bucket not in buckets:
            buckets[bucket] = Counter({"positiveClassCount": 0, "negativeClassCount": 0, "totalCount": 0})
        buckets[bucket]["totalCount"] += 1
        if record.is_positive_class:
            buckets[bucket]["positiveClassCount"] += 1
        else:
            buckets[bucket]["negativeClassCount"] += 1
    return {bucket: dict(counts) for bucket, counts in sorted(buckets.items())}


def _fraud_score_bucket_analysis(records: list[FeedbackDatasetRecord], score_buckets: tuple[float, ...]) -> list[dict[str, object]]:
    if len(score_buckets) < 2:
        raise ValueError("scoreBuckets must contain at least two boundaries")
    ordered = tuple(sorted(score_buckets))
    rows = []
    for lower, upper in zip(ordered, ordered[1:]):
        in_bucket = [
            record for record in records
            if record.fraud_score is not None and _score_in_bucket(float(record.fraud_score), lower, upper, upper == ordered[-1])
        ]
        positive_count = sum(1 for record in in_bucket if record.is_positive_class)
        rows.append({
            "bucket": f"{lower:.1f}-{upper:.1f}",
            "lowerInclusive": lower,
            "upperInclusive": upper == ordered[-1],
            "upper": upper,
            "recordCount": len(in_bucket),
            "positiveClassCount": positive_count,
            "negativeClassCount": len(in_bucket) - positive_count,
            "positiveClassShare": _metric_value(positive_count, len(in_bucket), "NO_BUCKET_RECORDS"),
        })
    return rows


def _score_in_bucket(score: float, lower: float, upper: float, inclusive_upper: bool) -> bool:
    if inclusive_upper:
        return lower <= score <= upper
    return lower <= score < upper


def build_ranked_classification_metrics(
        records: Iterable[FeedbackDatasetRecord],
        score_selector: Callable[[FeedbackDatasetRecord], float | None],
        top_k_values: Iterable[int] = DEFAULT_TOP_K_VALUES,
) -> dict[str, dict[str, dict[str, object]]]:
    requested_k_values = tuple(top_k_values)
    if not requested_k_values:
        raise ValueError("topK values must not be empty")
    if len(set(requested_k_values)) != len(requested_k_values):
        raise ValueError("topK values must not contain duplicates")
    for top_k in requested_k_values:
        if not isinstance(top_k, int) or isinstance(top_k, bool) or not 1 <= top_k <= MAX_DATASET_RECORDS:
            raise ValueError(f"topK values must be integers between 1 and {MAX_DATASET_RECORDS}")
    all_records = tuple(records)
    scored: list[tuple[FeedbackDatasetRecord, float]] = []
    for record in all_records:
        score = score_selector(record)
        if score is None:
            continue
        if not isinstance(score, (int, float)) or isinstance(score, bool):
            raise ValueError("ranking score must be numeric")
        numeric_score = float(score)
        if not math.isfinite(numeric_score) or not 0.0 <= numeric_score <= 1.0:
            raise ValueError("ranking score must be finite and between 0.0 and 1.0")
        scored.append((record, numeric_score))
    scored.sort(key=lambda item: (-item[1], item[0].evaluation_record_id))
    total_positive = sum(1 for record in all_records if record.is_positive_class)
    precision_at_k: dict[str, dict[str, object]] = {}
    recall_at_k: dict[str, dict[str, object]] = {}
    for top_k in requested_k_values:
        slice_ = scored[:min(top_k, len(scored))]
        positive_at_k = sum(1 for record, _ in slice_ if record.is_positive_class)
        actual_k = len(slice_)
        precision_row, recall_row = build_ranked_metric_rows(
            positive_at_k,
            total_positive,
            top_k,
            actual_k,
            bool(scored),
        )
        precision_at_k[str(top_k)] = precision_row
        recall_at_k[str(top_k)] = recall_row
    return {"precisionAtK": precision_at_k, "recallAtK": recall_at_k}


def build_ranked_metric_rows(
        positive_at_k: int,
        total_positive: int,
        requested_k: int,
        actual_k: int,
        scored_available: bool,
) -> tuple[dict[str, object], dict[str, object]]:
    precision_row = {
        "requestedK": requested_k,
        "actualK": actual_k,
        "value": _metric_value(positive_at_k, actual_k, "NO_SCORED_RECORDS"),
    }
    recall_row = {
        "requestedK": requested_k,
        "actualK": actual_k,
        "value": (
            _metric_value(positive_at_k, total_positive, "NO_POSITIVE_RECORDS")
            if scored_available
            else _metric_value(0, 0, "NO_SCORED_RECORDS")
        ),
    }
    return precision_row, recall_row


def _metric_value(numerator: int, denominator: int, unavailable_reason: str) -> dict[str, float | bool | str | None]:
    if denominator == 0:
        return {"available": False, "reason": unavailable_reason, "value": None}
    return {"available": True, "reason": None, "value": round(numerator / denominator, 6)}
