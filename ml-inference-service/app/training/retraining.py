from __future__ import annotations

from dataclasses import dataclass

from app.data.dataset import Dataset
from app.training.train import train_with_evaluation


@dataclass(frozen=True)
class PromotionThresholds:
    """Business thresholds for challenger promotion."""

    max_false_positive_rate_increase: float = 0.02
    max_alert_rate: float = 0.20
    min_alert_rate: float = 0.001


@dataclass(frozen=True)
class RetrainingComparison:
    """Comparison between current and challenger model evaluation."""

    current_pr_auc: float
    challenger_pr_auc: float
    promote_challenger: bool
    challenger_evaluation: dict[str, object]
    decision: dict[str, object]


def compare_retrained_model(
        feedback_dataset: Dataset,
        current_evaluation: dict[str, object],
        epochs: int,
        learning_rate: float,
        thresholds: PromotionThresholds | None = None,
) -> RetrainingComparison:
    """Retrain on analyst feedback and compare challenger on held-out metrics."""
    if feedback_dataset.size == 0:
        raise ValueError("feedback_dataset must contain labelled examples.")
    _, _, challenger_evaluation = train_with_evaluation(feedback_dataset, epochs, learning_rate)
    split_metadata = challenger_evaluation.get("splitMetadata")
    if not isinstance(split_metadata, dict) or split_metadata.get("testRows", 0) <= 0:
        raise ValueError("challenger evaluation must include held-out test rows.")
    current_pr_auc = float(current_evaluation.get("heldOutPrAuc", current_evaluation.get("prAuc", 0.0)))
    challenger_pr_auc = float(challenger_evaluation.get("prAuc", 0.0))
    thresholds = thresholds or PromotionThresholds()
    decision = _promotion_decision(current_evaluation, challenger_evaluation, thresholds)
    return RetrainingComparison(
        current_pr_auc=current_pr_auc,
        challenger_pr_auc=challenger_pr_auc,
        promote_challenger=bool(decision["promote"]),
        challenger_evaluation=challenger_evaluation,
        decision=decision,
    )


def _promotion_decision(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
        thresholds: PromotionThresholds,
) -> dict[str, object]:
    current_optimal = _optimal(current_evaluation)
    challenger_optimal = _optimal(challenger_evaluation)
    current_pr_auc = float(current_evaluation.get("heldOutPrAuc", current_evaluation.get("prAuc", 0.0)))
    challenger_pr_auc = float(challenger_evaluation.get("prAuc", 0.0))
    current_cost = _cost(current_evaluation)
    challenger_cost = _cost(challenger_evaluation)
    criteria = {
        "prAucImproved": challenger_pr_auc > current_pr_auc,
        "falsePositiveRateWithinThreshold": (
            float(challenger_optimal.get("falsePositiveRate", 0.0))
            <= float(current_optimal.get("falsePositiveRate", 0.0)) + thresholds.max_false_positive_rate_increase
        ),
        "alertRateWithinRange": (
            thresholds.min_alert_rate
            <= float(challenger_optimal.get("alertRate", 0.0))
            <= thresholds.max_alert_rate
        ),
        "expectedCostNotWorse": challenger_cost <= current_cost,
    }
    return {
        "promote": all(criteria.values()),
        "criteria": criteria,
        "metrics": {
            "currentPrAuc": current_pr_auc,
            "challengerPrAuc": challenger_pr_auc,
            "currentFalsePositiveRate": float(current_optimal.get("falsePositiveRate", 0.0)),
            "challengerFalsePositiveRate": float(challenger_optimal.get("falsePositiveRate", 0.0)),
            "currentAlertRate": float(current_optimal.get("alertRate", 0.0)),
            "challengerAlertRate": float(challenger_optimal.get("alertRate", 0.0)),
            "currentExpectedCost": current_cost,
            "challengerExpectedCost": challenger_cost,
        },
        "thresholds": {
            "maxFalsePositiveRateIncrease": thresholds.max_false_positive_rate_increase,
            "minAlertRate": thresholds.min_alert_rate,
            "maxAlertRate": thresholds.max_alert_rate,
        },
    }


def _optimal(evaluation: dict[str, object]) -> dict[str, object]:
    value = evaluation.get("optimalThreshold")
    return value if isinstance(value, dict) else {}


def _cost(evaluation: dict[str, object]) -> float:
    cost_evaluation = evaluation.get("costEvaluation")
    if not isinstance(cost_evaluation, dict):
        return 0.0
    optimal = cost_evaluation.get("optimalCostThreshold")
    if not isinstance(optimal, dict):
        return 0.0
    return float(optimal.get("totalCost", 0.0))
