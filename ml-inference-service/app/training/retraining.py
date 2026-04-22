from __future__ import annotations

from dataclasses import dataclass

from app.data.dataset import Dataset
from app.training.train import train_with_evaluation


@dataclass(frozen=True)
class RetrainingComparison:
    """Comparison between current and challenger model evaluation."""

    current_pr_auc: float
    challenger_pr_auc: float
    promote_challenger: bool
    challenger_evaluation: dict[str, object]


def compare_retrained_model(
        feedback_dataset: Dataset,
        current_evaluation: dict[str, object],
        epochs: int,
        learning_rate: float,
) -> RetrainingComparison:
    """Retrain on analyst feedback and compare challenger against current metrics."""
    if feedback_dataset.size == 0:
        raise ValueError("feedback_dataset must contain labelled examples.")
    _, _, challenger_evaluation = train_with_evaluation(feedback_dataset, epochs, learning_rate)
    current_pr_auc = float(current_evaluation.get("prAuc", 0.0))
    challenger_pr_auc = float(challenger_evaluation.get("prAuc", 0.0))
    return RetrainingComparison(
        current_pr_auc=current_pr_auc,
        challenger_pr_auc=challenger_pr_auc,
        promote_challenger=challenger_pr_auc >= current_pr_auc,
        challenger_evaluation=challenger_evaluation,
    )
