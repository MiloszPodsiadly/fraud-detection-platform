from __future__ import annotations

from dataclasses import dataclass

from app.data.dataset import Dataset
from app.training.train import train_with_evaluation


@dataclass(frozen=True)
class ChallengerComparisonThresholds:
    """Bounds used only to interpret observed challenger diagnostics."""

    max_false_positive_rate_increase: float = 0.02
    max_alert_rate: float = 0.20
    min_alert_rate: float = 0.001
    alert_budget: float | None = None
    max_segment_pr_auc_drop: float = 0.15
    max_out_of_time_pr_auc_drop: float = 0.20
    max_out_of_time_cost_increase: float = 500.0


@dataclass(frozen=True)
class ChallengerDiagnosticComparison:
    """Non-decisioning comparison between current and challenger evaluation."""

    current_pr_auc: float | None
    challenger_pr_auc: float | None
    diagnostic_outcome: str
    challenger_evaluation: dict[str, object]
    diagnostics: dict[str, object]


def evaluate_challenger_diagnostics(
        feedback_dataset: Dataset,
        current_evaluation: dict[str, object],
        epochs: int,
        learning_rate: float,
        thresholds: ChallengerComparisonThresholds | None = None,
        model_type: str = "logistic",
        training_mode: str = "production",
) -> ChallengerDiagnosticComparison:
    """Retrain offline and report observed diagnostics without lifecycle authority."""
    if feedback_dataset.size == 0:
        raise ValueError("feedback_dataset must contain labelled examples.")
    _, _, challenger_evaluation = train_with_evaluation(
        feedback_dataset,
        epochs,
        learning_rate,
        training_mode=training_mode,
        model_type=model_type,
    )
    current_pr_auc = _optional_metric(current_evaluation, "heldOutPrAuc", "prAuc")
    challenger_pr_auc = _optional_metric(challenger_evaluation, "prAuc")
    thresholds = thresholds or ChallengerComparisonThresholds()
    diagnostics = _diagnostic_assessment(
        current_evaluation,
        challenger_evaluation,
        thresholds,
        feedback_dataset,
    )
    return ChallengerDiagnosticComparison(
        current_pr_auc=current_pr_auc,
        challenger_pr_auc=challenger_pr_auc,
        diagnostic_outcome=diagnostics["outcome"],
        challenger_evaluation=challenger_evaluation,
        diagnostics=diagnostics,
    )


def _diagnostic_assessment(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
        thresholds: ChallengerComparisonThresholds,
        feedback_dataset: Dataset | None = None,
) -> dict[str, object]:
    insufficient_reasons = _insufficient_evidence_reasons(current_evaluation, challenger_evaluation)
    current_optimal = _optimal(current_evaluation)
    challenger_optimal = _optimal(challenger_evaluation)
    current_pr_auc = _optional_metric(current_evaluation, "heldOutPrAuc", "prAuc")
    challenger_pr_auc = _optional_metric(challenger_evaluation, "prAuc")
    current_cost = _cost(current_evaluation)
    challenger_cost = _cost(challenger_evaluation)
    alert_budget = _budget(thresholds.alert_budget, current_evaluation, challenger_evaluation)
    core_checks = {
        "prAucImproved": (
            current_pr_auc is not None
            and challenger_pr_auc is not None
            and challenger_pr_auc > current_pr_auc
        ),
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
    if alert_budget:
        core_checks["budgetExpectedCostNotWorse"] = float(alert_budget["challenger"]["expectedCost"]) <= float(alert_budget["current"]["expectedCost"])
        core_checks["budgetFraudCaptureNotWorse"] = float(alert_budget["challenger"]["fraudCaptureRate"]) >= float(alert_budget["current"]["fraudCaptureRate"])

    segment_check = _segment_regression_check(current_evaluation, challenger_evaluation, thresholds)
    stability_check = _stability_check(challenger_evaluation, thresholds)
    failed_core = [name for name, passed in core_checks.items() if not passed]
    failed_soft = []
    if not segment_check["passed"]:
        failed_soft.append("segmentRegression")
    if not stability_check["passed"]:
        failed_soft.append("stabilityRegression")

    if insufficient_reasons:
        outcome = "INSUFFICIENT_EVIDENCE"
        summary = "Observed data is insufficient for a bounded challenger comparison."
    elif failed_core:
        outcome = "NOT_BETTER_ON_OBSERVED_METRICS"
        summary = "Challenger was not better on the configured observed metrics."
    elif failed_soft:
        outcome = "REQUIRES_SHADOW_REVIEW"
        summary = "Observed metrics require additional shadow review for segment or stability risk."
    else:
        outcome = "BETTER_ON_OBSERVED_METRICS"
        summary = "Challenger was better within the configured observed diagnostic bounds."

    passed_checks = [name for name, passed in core_checks.items() if passed]
    if segment_check["passed"]:
        passed_checks.append("segmentRegression")
    if stability_check["passed"]:
        passed_checks.append("stabilityRegression")
    failed_checks = failed_core + failed_soft
    return {
        "outcome": outcome,
        "summary": summary,
        "passedChecks": passed_checks,
        "failedChecks": failed_checks,
        "insufficientEvidenceReasons": insufficient_reasons,
        "criteria": {
            **core_checks,
            "segmentRegression": segment_check["passed"],
            "stabilityRegression": stability_check["passed"],
        },
        "observedMetrics": {
            "currentPrAuc": current_pr_auc,
            "challengerPrAuc": challenger_pr_auc,
            "currentFalsePositiveRate": float(current_optimal.get("falsePositiveRate", 0.0)),
            "challengerFalsePositiveRate": float(challenger_optimal.get("falsePositiveRate", 0.0)),
            "currentAlertRate": float(current_optimal.get("alertRate", 0.0)),
            "challengerAlertRate": float(challenger_optimal.get("alertRate", 0.0)),
            "currentExpectedCost": current_cost,
            "challengerExpectedCost": challenger_cost,
            "alertBudget": alert_budget,
            "segmentAssessment": segment_check,
            "stabilityAssessment": stability_check,
        },
        "evidence": {
            "feedbackDatasetRows": feedback_dataset.size if feedback_dataset is not None else None,
            "datasetProvenance": dict(feedback_dataset.metadata) if feedback_dataset is not None else {},
            "evaluationWindows": _evaluation_window_metadata(current_evaluation, challenger_evaluation),
        },
        "comparisonThresholds": {
            "maxFalsePositiveRateIncrease": thresholds.max_false_positive_rate_increase,
            "minAlertRate": thresholds.min_alert_rate,
            "maxAlertRate": thresholds.max_alert_rate,
            "alertBudget": thresholds.alert_budget,
            "maxSegmentPrAucDrop": thresholds.max_segment_pr_auc_drop,
            "maxOutOfTimePrAucDrop": thresholds.max_out_of_time_pr_auc_drop,
            "maxOutOfTimeCostIncrease": thresholds.max_out_of_time_cost_increase,
        },
        "limitations": [
            "Observed metrics do not approve promotion or production-primary decisioning.",
            "Analyst feedback is an evaluation signal, not certified fraud ground truth.",
            "No registry, deployment, scoring mode, or runtime authority is mutated.",
        ],
    }


def _insufficient_evidence_reasons(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
) -> list[str]:
    reasons = []
    split_metadata = challenger_evaluation.get("splitMetadata")
    if not isinstance(split_metadata, dict) or split_metadata.get("testRows", 0) <= 0:
        reasons.append("CHALLENGER_HELD_OUT_ROWS_MISSING")
    if _optional_metric(current_evaluation, "heldOutPrAuc", "prAuc") is None:
        reasons.append("CURRENT_PR_AUC_MISSING")
    if _optional_metric(challenger_evaluation, "prAuc") is None:
        reasons.append("CHALLENGER_PR_AUC_MISSING")
    return reasons


def _optional_metric(evaluation: dict[str, object], *names: str) -> float | None:
    for name in names:
        value = evaluation.get(name)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    return None


def _budget(
        alert_budget: float | None,
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
) -> dict[str, object] | None:
    if alert_budget is None:
        return None
    current = _budget_entry(current_evaluation, alert_budget)
    challenger = _budget_entry(challenger_evaluation, alert_budget)
    if current is None or challenger is None:
        return None
    return {"budget": alert_budget, "current": current, "challenger": challenger}


def _evaluation_window_metadata(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
) -> dict[str, object]:
    out_of_time = challenger_evaluation.get("outOfTimeEvaluation")
    return {
        "current": current_evaluation.get("splitMetadata", {}),
        "challenger": challenger_evaluation.get("splitMetadata", {}),
        "challengerOutOfTime": out_of_time.get("splitMetadata", {}) if isinstance(out_of_time, dict) else {},
    }


def _budget_entry(evaluation: dict[str, object], alert_budget: float) -> dict[str, object] | None:
    budget_evaluation = evaluation.get("budgetEvaluation")
    if not isinstance(budget_evaluation, dict):
        return None
    budgets = budget_evaluation.get("budgets")
    if not isinstance(budgets, list):
        return None
    return next(
        (entry for entry in budgets if isinstance(entry, dict) and abs(float(entry.get("alertBudget", -1.0)) - alert_budget) < 0.000001),
        None,
    )


def _segment_regression_check(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
        thresholds: ChallengerComparisonThresholds,
) -> dict[str, object]:
    current_segments = current_evaluation.get("segmentEvaluation")
    challenger_segments = challenger_evaluation.get("segmentEvaluation")
    if not isinstance(current_segments, dict) or not isinstance(challenger_segments, dict):
        return {"passed": True, "regressions": []}
    regressions = []
    for dimension, current_by_segment in current_segments.items():
        challenger_by_segment = challenger_segments.get(dimension)
        if not isinstance(current_by_segment, dict) or not isinstance(challenger_by_segment, dict):
            continue
        for segment, current_metrics in current_by_segment.items():
            challenger_metrics = challenger_by_segment.get(segment)
            if not isinstance(current_metrics, dict) or not isinstance(challenger_metrics, dict):
                continue
            drop = float(current_metrics.get("prAuc", 0.0)) - float(challenger_metrics.get("prAuc", 0.0))
            if drop > thresholds.max_segment_pr_auc_drop:
                regressions.append({"dimension": dimension, "segment": segment, "prAucDrop": round(drop, 6)})
    return {"passed": not regressions, "regressions": regressions}


def _stability_check(
        evaluation: dict[str, object],
        thresholds: ChallengerComparisonThresholds,
) -> dict[str, object]:
    stability = evaluation.get("stabilityAssessment")
    if not isinstance(stability, dict):
        return {"passed": True, "reason": "missing stability assessment"}
    failures = []
    if float(stability.get("prAucDelta", 0.0)) > thresholds.max_out_of_time_pr_auc_drop:
        failures.append("prAucDelta")
    if float(stability.get("expectedCostDelta", 0.0)) > thresholds.max_out_of_time_cost_increase:
        failures.append("expectedCostDelta")
    return {"passed": not failures, "failures": failures, "metrics": stability}


def _optimal(evaluation: dict[str, object]) -> dict[str, object]:
    deployed = evaluation.get("deployedAlertThresholdMetrics")
    if isinstance(deployed, dict):
        return deployed
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
