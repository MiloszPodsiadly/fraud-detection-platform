from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ThresholdMetrics:
    """Metrics calculated at one fraud alert threshold."""

    threshold: float
    precision: float
    recall: float
    alert_rate: float
    fraud_capture_rate: float
    false_positive_rate: float


def evaluate_scores(
        y_true: list[int],
        y_score: list[float],
        thresholds: list[float] | None = None,
        top_k: list[int] | None = None,
) -> dict[str, object]:
    """Evaluate fraud model scores with ranking and business metrics."""
    if len(y_true) != len(y_score):
        raise ValueError("y_true and y_score must contain the same number of rows.")
    if not y_true:
        raise ValueError("evaluation requires at least one row.")

    thresholds = thresholds or [0.10, 0.20, 0.30, 0.45, 0.60, 0.75, 0.90]
    top_k = top_k or [10, 50, 100]
    positives = sum(1 for label in y_true if label == 1)
    negatives = len(y_true) - positives
    threshold_reports = [
        _threshold_metrics(y_true, y_score, threshold, positives, negatives)
        for threshold in thresholds
    ]
    optimal = max(threshold_reports, key=lambda item: (_f1(item.precision, item.recall), item.precision))

    return {
        "rows": len(y_true),
        "positiveLabels": positives,
        "negativeLabels": negatives,
        "rocAuc": round(_roc_auc(y_true, y_score), 6),
        "prAuc": round(_pr_auc(y_true, y_score), 6),
        "precisionAtK": {
            str(k): round(_precision_at_k(y_true, y_score, k), 6)
            for k in top_k
            if k > 0
        },
        "recallAtK": {
            str(k): round(_recall_at_k(y_true, y_score, k, positives), 6)
            for k in top_k
            if k > 0
        },
        "thresholds": [
            {
                "threshold": item.threshold,
                "precision": round(item.precision, 6),
                "recall": round(item.recall, 6),
                "alertRate": round(item.alert_rate, 6),
                "fraudCaptureRate": round(item.fraud_capture_rate, 6),
                "falsePositiveRate": round(item.false_positive_rate, 6),
                "f1": round(_f1(item.precision, item.recall), 6),
            }
            for item in threshold_reports
        ],
        "optimalThreshold": {
            "threshold": optimal.threshold,
            "precision": round(optimal.precision, 6),
            "recall": round(optimal.recall, 6),
            "alertRate": round(optimal.alert_rate, 6),
            "fraudCaptureRate": round(optimal.fraud_capture_rate, 6),
            "falsePositiveRate": round(optimal.false_positive_rate, 6),
            "f1": round(_f1(optimal.precision, optimal.recall), 6),
        },
    }


def write_report(report: dict[str, object], path: Path) -> None:
    """Write an evaluation report as JSON."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def cli_summary(report: dict[str, object]) -> str:
    """Create a compact fraud-analyst-oriented CLI summary."""
    optimal = report["optimalThreshold"]
    assert isinstance(optimal, dict)
    return (
        f"evaluation rows={report['rows']} positives={report['positiveLabels']} "
        f"prAuc={report['prAuc']} rocAuc={report['rocAuc']} "
        f"optimalThreshold={optimal['threshold']} "
        f"precision={optimal['precision']} recall={optimal['recall']} "
        f"alertRate={optimal['alertRate']} falsePositiveRate={optimal['falsePositiveRate']}"
    )


def _threshold_metrics(
        y_true: list[int],
        y_score: list[float],
        threshold: float,
        positives: int,
        negatives: int,
) -> ThresholdMetrics:
    predicted = [score >= threshold for score in y_score]
    true_positive = sum(1 for label, is_alert in zip(y_true, predicted) if label == 1 and is_alert)
    false_positive = sum(1 for label, is_alert in zip(y_true, predicted) if label == 0 and is_alert)
    alert_count = sum(1 for is_alert in predicted if is_alert)
    precision = true_positive / alert_count if alert_count else 0.0
    recall = true_positive / positives if positives else 0.0
    false_positive_rate = false_positive / negatives if negatives else 0.0
    return ThresholdMetrics(
        threshold=threshold,
        precision=precision,
        recall=recall,
        alert_rate=alert_count / len(y_true),
        fraud_capture_rate=recall,
        false_positive_rate=false_positive_rate,
    )


def _precision_at_k(y_true: list[int], y_score: list[float], k: int) -> float:
    ranked = _ranked_labels(y_true, y_score)[:min(k, len(y_true))]
    return sum(ranked) / len(ranked) if ranked else 0.0


def _recall_at_k(y_true: list[int], y_score: list[float], k: int, positives: int) -> float:
    if positives == 0:
        return 0.0
    ranked = _ranked_labels(y_true, y_score)[:min(k, len(y_true))]
    return sum(ranked) / positives


def _ranked_labels(y_true: list[int], y_score: list[float]) -> list[int]:
    return [
        label for label, _ in sorted(
            zip(y_true, y_score),
            key=lambda item: item[1],
            reverse=True,
        )
    ]


def _roc_auc(y_true: list[int], y_score: list[float]) -> float:
    positives = sum(y_true)
    negatives = len(y_true) - positives
    if positives == 0 or negatives == 0:
        return 0.0

    sorted_pairs = sorted(zip(y_score, y_true), key=lambda item: item[0])
    rank_sum = 0.0
    index = 0
    while index < len(sorted_pairs):
        next_index = index + 1
        while next_index < len(sorted_pairs) and sorted_pairs[next_index][0] == sorted_pairs[index][0]:
            next_index += 1
        average_rank = (index + 1 + next_index) / 2.0
        rank_sum += sum(label for _, label in sorted_pairs[index:next_index]) * average_rank
        index = next_index

    return (rank_sum - positives * (positives + 1) / 2.0) / (positives * negatives)


def _pr_auc(y_true: list[int], y_score: list[float]) -> float:
    positives = sum(y_true)
    if positives == 0:
        return 0.0

    ranked = sorted(zip(y_true, y_score), key=lambda item: item[1], reverse=True)
    true_positive = 0
    previous_recall = 0.0
    area = 0.0
    for index, (label, _) in enumerate(ranked, start=1):
        if label == 1:
            true_positive += 1
        recall = true_positive / positives
        precision = true_positive / index
        area += (recall - previous_recall) * precision
        previous_recall = recall
    return area


def _f1(precision: float, recall: float) -> float:
    if precision + recall == 0:
        return 0.0
    return 2 * precision * recall / (precision + recall)
