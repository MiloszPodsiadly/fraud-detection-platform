from __future__ import annotations

from collections import Counter
from typing import Iterable

from offline_evaluation.fdp123.models import Fdp123Dataset, Fdp123DatasetRecord


DEFAULT_TOP_K_VALUES = (10, 25, 50, 100)
DEFAULT_SCORE_BUCKETS = (0.0, 0.2, 0.4, 0.6, 0.8, 1.0)
DEFAULT_MIN_SAMPLE_SIZE_WARNING_THRESHOLD = 30
HIGH_RISK_LEVELS = {"HIGH", "CRITICAL"}
LOW_RISK_LEVELS = {"LOW", "MEDIUM"}


def build_fdp123_metrics(
        dataset: Fdp123Dataset,
        top_k_values: Iterable[int] = DEFAULT_TOP_K_VALUES,
        score_buckets: Iterable[float] = DEFAULT_SCORE_BUCKETS,
        min_sample_size_warning_threshold: int = DEFAULT_MIN_SAMPLE_SIZE_WARNING_THRESHOLD,
) -> dict[str, object]:
    records = list(dataset.records)
    positives = [record for record in records if record.is_positive_class]
    negatives = [record for record in records if record.is_negative_class]
    scored = sorted(
        (record for record in records if record.fraud_score is not None),
        key=lambda record: (-float(record.fraud_score), record.evaluation_record_id),
    )
    warnings = _warnings(dataset, records, positives, negatives, min_sample_size_warning_threshold)
    return {
        "metricBasis": "fdp123_feedback_dataset_offline_diagnostic_v1",
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
        "precisionAtK": _precision_at_k(scored, tuple(top_k_values)),
        "recallAtK": _recall_at_k(scored, len(positives), tuple(top_k_values)),
        "missingFraudScoreCount": sum(1 for record in records if record.fraud_score is None),
        "missingAlertRecommendedCount": sum(1 for record in records if record.alert_recommended is None),
        "missingRiskLevelCount": sum(1 for record in records if record.risk_level is None),
        "warnings": warnings,
    }


def _warnings(
        dataset: Fdp123Dataset,
        records: list[Fdp123DatasetRecord],
        positives: list[Fdp123DatasetRecord],
        negatives: list[Fdp123DatasetRecord],
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


def _alert_recommended_confusion_matrix(records: list[Fdp123DatasetRecord]) -> dict[str, object]:
    evaluated = [record for record in records if record.alert_recommended is not None]
    true_positive = sum(1 for record in evaluated if record.alert_recommended is True and record.is_positive_class)
    false_positive = sum(1 for record in evaluated if record.alert_recommended is True and record.is_negative_class)
    true_negative = sum(1 for record in evaluated if record.alert_recommended is False and record.is_negative_class)
    false_negative = sum(1 for record in evaluated if record.alert_recommended is False and record.is_positive_class)
    return {
        "recordsWithSignal": len(evaluated),
        "truePositive": true_positive,
        "falsePositive": false_positive,
        "trueNegative": true_negative,
        "falseNegative": false_negative,
        "missingAlertRecommendedCount": sum(1 for record in records if record.alert_recommended is None),
        "precision": _metric_value(true_positive, true_positive + false_positive, "NO_PREDICTED_POSITIVES"),
        "recall": _metric_value(true_positive, true_positive + false_negative, "NO_ACTUAL_POSITIVES"),
        "falsePositiveRate": _metric_value(false_positive, false_positive + true_negative, "NO_ACTUAL_NEGATIVES"),
        "falseNegativeRate": _metric_value(false_negative, false_negative + true_positive, "NO_ACTUAL_POSITIVES"),
    }


def _risk_level_breakdown(records: list[Fdp123DatasetRecord]) -> dict[str, dict[str, int]]:
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


def _fraud_score_bucket_analysis(records: list[Fdp123DatasetRecord], score_buckets: tuple[float, ...]) -> list[dict[str, object]]:
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


def _precision_at_k(scored: list[Fdp123DatasetRecord], top_k_values: tuple[int, ...]) -> dict[str, dict[str, object]]:
    result = {}
    for top_k in top_k_values:
        if top_k < 1:
            raise ValueError("topK values must be positive")
        slice_ = scored[: min(top_k, len(scored))]
        result[str(top_k)] = {
            "requestedK": top_k,
            "actualK": len(slice_),
            "value": _metric_value(sum(1 for record in slice_ if record.is_positive_class), len(slice_), "NO_SCORED_RECORDS"),
        }
    return result


def _recall_at_k(
        scored: list[Fdp123DatasetRecord],
        total_positive: int,
        top_k_values: tuple[int, ...],
) -> dict[str, dict[str, object]]:
    result = {}
    for top_k in top_k_values:
        if top_k < 1:
            raise ValueError("topK values must be positive")
        slice_ = scored[: min(top_k, len(scored))]
        result[str(top_k)] = {
            "requestedK": top_k,
            "actualK": len(slice_),
            "value": _metric_value(sum(1 for record in slice_ if record.is_positive_class), total_positive, "NO_POSITIVE_RECORDS"),
        }
    return result


def _metric_value(numerator: int, denominator: int, unavailable_reason: str) -> dict[str, float | bool | str | None]:
    if denominator == 0:
        return {"available": False, "reason": unavailable_reason, "value": None}
    return {"available": True, "reason": None, "value": round(numerator / denominator, 6)}
