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
    false_positives: int
    false_negatives: int
    total_cost: float


def evaluate_scores(
        y_true: list[int],
        y_score: list[float],
        thresholds: list[float] | None = None,
        top_k: list[int] | None = None,
        cost_false_positive: float = 25.0,
        cost_false_negative: float = 500.0,
        alert_budgets: list[float] | None = None,
        segment_rows: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    """Evaluate fraud model scores with ranking and business metrics."""
    if len(y_true) != len(y_score):
        raise ValueError("y_true and y_score must contain the same number of rows.")
    if not y_true:
        raise ValueError("evaluation requires at least one row.")

    thresholds = thresholds or [0.10, 0.20, 0.30, 0.45, 0.60, 0.75, 0.90]
    top_k = top_k or [10, 50, 100]
    alert_budgets = alert_budgets or [0.005, 0.01, 0.02, 0.05]
    positives = sum(1 for label in y_true if label == 1)
    negatives = len(y_true) - positives
    threshold_reports = [
        _threshold_metrics(
            y_true,
            y_score,
            threshold,
            positives,
            negatives,
            cost_false_positive,
            cost_false_negative,
        )
        for threshold in thresholds
    ]
    optimal = max(threshold_reports, key=lambda item: (_f1(item.precision, item.recall), item.precision))
    optimal_cost = min(threshold_reports, key=lambda item: (item.total_cost, -item.fraud_capture_rate))

    report = {
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
                "falsePositives": item.false_positives,
                "falseNegatives": item.false_negatives,
                "totalCost": round(item.total_cost, 6),
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
        "costEvaluation": {
            "costFalsePositive": cost_false_positive,
            "costFalseNegative": cost_false_negative,
            "costCurves": [
                {
                    "threshold": item.threshold,
                    "falsePositives": item.false_positives,
                    "falseNegatives": item.false_negatives,
                    "totalCost": round(item.total_cost, 6),
                }
                for item in threshold_reports
            ],
            "optimalCostThreshold": {
                "threshold": optimal_cost.threshold,
                "totalCost": round(optimal_cost.total_cost, 6),
                "falsePositives": optimal_cost.false_positives,
                "falseNegatives": optimal_cost.false_negatives,
            },
        },
        "budgetEvaluation": _budget_evaluation(
            y_true,
            y_score,
            alert_budgets,
            cost_false_positive,
            cost_false_negative,
        ),
    }
    if segment_rows is not None:
        report["segmentEvaluation"] = _segment_evaluation(
            y_true,
            y_score,
            segment_rows,
            cost_false_positive,
            cost_false_negative,
        )
    return report


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
        cost_false_positive: float,
        cost_false_negative: float,
) -> ThresholdMetrics:
    predicted = [score >= threshold for score in y_score]
    true_positive = sum(1 for label, is_alert in zip(y_true, predicted) if label == 1 and is_alert)
    false_positive = sum(1 for label, is_alert in zip(y_true, predicted) if label == 0 and is_alert)
    false_negative = sum(1 for label, is_alert in zip(y_true, predicted) if label == 1 and not is_alert)
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
        false_positives=false_positive,
        false_negatives=false_negative,
        total_cost=false_positive * cost_false_positive + false_negative * cost_false_negative,
    )


def _budget_evaluation(
        y_true: list[int],
        y_score: list[float],
        alert_budgets: list[float],
        cost_false_positive: float,
        cost_false_negative: float,
) -> dict[str, object]:
    ranked = sorted(zip(y_true, y_score), key=lambda item: item[1], reverse=True)
    budgets = []
    for budget in alert_budgets:
        alert_count = max(1, min(len(ranked), int(round(len(ranked) * budget))))
        threshold = ranked[alert_count - 1][1] if ranked else 1.0
        metrics = _threshold_metrics(
            y_true,
            y_score,
            threshold,
            sum(y_true),
            len(y_true) - sum(y_true),
            cost_false_positive,
            cost_false_negative,
        )
        budgets.append({
            "alertBudget": budget,
            "recommendedThreshold": round(threshold, 6),
            "alertCount": alert_count,
            "precision": round(metrics.precision, 6),
            "fraudCaptureRate": round(metrics.fraud_capture_rate, 6),
            "falsePositiveCount": metrics.false_positives,
            "expectedCost": round(metrics.total_cost, 6),
        })
    best = min(budgets, key=lambda item: (item["expectedCost"], -item["fraudCaptureRate"]))
    return {
        "budgets": budgets,
        "recommended": best,
    }


def _segment_evaluation(
        y_true: list[int],
        y_score: list[float],
        segment_rows: list[dict[str, object]],
        cost_false_positive: float,
        cost_false_negative: float,
) -> dict[str, object]:
    if len(segment_rows) != len(y_true):
        raise ValueError("segment_rows must match y_true length.")
    dimensions = {
        "customerSegment": lambda row: row.get("customerSegment") or row.get("customer_segment"),
        "merchantCategory": lambda row: row.get("merchantCategory") or row.get("merchant_category"),
        "country": lambda row: row.get("country") or _raw_value(row, "country"),
        "fraudScenario": lambda row: _scenario(row),
    }
    output: dict[str, object] = {}
    for dimension, extractor in dimensions.items():
        grouped: dict[str, list[int]] = {}
        for index, row in enumerate(segment_rows):
            value = extractor(row)
            if value is None:
                continue
            grouped.setdefault(str(value), []).append(index)
        segments = {}
        for segment, indices in grouped.items():
            labels = [y_true[index] for index in indices]
            scores = [y_score[index] for index in indices]
            if len(labels) < 2:
                continue
            segment_report = evaluate_scores(
                labels,
                scores,
                thresholds=[0.45],
                top_k=[min(10, len(labels))],
                cost_false_positive=cost_false_positive,
                cost_false_negative=cost_false_negative,
            )
            optimal = segment_report["optimalThreshold"]
            cost = segment_report["costEvaluation"]["optimalCostThreshold"]
            segments[segment] = {
                "rows": segment_report["rows"],
                "positiveLabels": segment_report["positiveLabels"],
                "prAuc": segment_report["prAuc"],
                "fraudCaptureRate": optimal["fraudCaptureRate"],
                "falsePositiveRate": optimal["falsePositiveRate"],
                "alertRate": optimal["alertRate"],
                "expectedCost": cost["totalCost"],
            }
        if segments:
            output[dimension] = segments
    return output


def _raw_value(row: dict[str, object], name: str) -> object:
    raw = row.get("raw_transaction")
    return raw.get(name) if isinstance(raw, dict) else None


def _scenario(row: dict[str, object]) -> object:
    metadata = row.get("metadata")
    return metadata.get("scenario") if isinstance(metadata, dict) else row.get("fraudScenario")


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
