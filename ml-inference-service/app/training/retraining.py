from __future__ import annotations

import math
from dataclasses import dataclass

from app.data.dataset import Dataset
from app.training.train import train_with_evaluation


PASS = "PASS"
FAIL = "FAIL"
NOT_EVALUATED = "NOT_EVALUATED"


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
    current_optimal = _optimal(current_evaluation)
    challenger_optimal = _optimal(challenger_evaluation)
    current_pr_auc = _optional_metric(current_evaluation, "heldOutPrAuc", "prAuc")
    challenger_pr_auc = _optional_metric(challenger_evaluation, "prAuc")
    current_cost = _cost(current_evaluation)
    challenger_cost = _cost(challenger_evaluation)
    current_false_positive_rate = _bounded_metric(current_optimal, "falsePositiveRate", 0.0, 1.0)
    challenger_false_positive_rate = _bounded_metric(challenger_optimal, "falsePositiveRate", 0.0, 1.0)
    current_alert_rate = _bounded_metric(current_optimal, "alertRate", 0.0, 1.0)
    challenger_alert_rate = _bounded_metric(challenger_optimal, "alertRate", 0.0, 1.0)
    alert_budget = _budget(thresholds.alert_budget, current_evaluation, challenger_evaluation)
    core_checks = {
        "prAucImproved": _comparison_state(
            current_pr_auc,
            challenger_pr_auc,
            passed=challenger_pr_auc is not None
            and current_pr_auc is not None
            and challenger_pr_auc > current_pr_auc,
        ),
        "falsePositiveRateWithinThreshold": _comparison_state(
            current_false_positive_rate,
            challenger_false_positive_rate,
            passed=challenger_false_positive_rate is not None
            and current_false_positive_rate is not None
            and challenger_false_positive_rate
            <= current_false_positive_rate + thresholds.max_false_positive_rate_increase,
        ),
        "alertRateWithinRange": _single_metric_state(
            challenger_alert_rate,
            passed=challenger_alert_rate is not None
            and thresholds.min_alert_rate <= challenger_alert_rate <= thresholds.max_alert_rate,
        ),
        "expectedCostNotWorse": _comparison_state(
            current_cost,
            challenger_cost,
            passed=challenger_cost is not None
            and current_cost is not None
            and challenger_cost <= current_cost,
        ),
    }
    if thresholds.alert_budget is not None:
        if alert_budget is None:
            core_checks["budgetExpectedCostNotWorse"] = NOT_EVALUATED
            core_checks["budgetFraudCaptureNotWorse"] = NOT_EVALUATED
        else:
            core_checks["budgetExpectedCostNotWorse"] = (
                PASS
                if alert_budget["challenger"]["expectedCost"] <= alert_budget["current"]["expectedCost"]
                else FAIL
            )
            core_checks["budgetFraudCaptureNotWorse"] = (
                PASS
                if alert_budget["challenger"]["fraudCaptureRate"]
                >= alert_budget["current"]["fraudCaptureRate"]
                else FAIL
            )

    segment_check = _segment_regression_check(current_evaluation, challenger_evaluation, thresholds)
    stability_check = _stability_check(challenger_evaluation, thresholds)
    insufficient_reasons = _insufficient_evidence_reasons(current_evaluation, challenger_evaluation)
    core_reason_codes = {
        "falsePositiveRateWithinThreshold": "FALSE_POSITIVE_RATE_EVIDENCE_MISSING",
        "alertRateWithinRange": "ALERT_RATE_EVIDENCE_MISSING",
        "expectedCostNotWorse": "EXPECTED_COST_EVIDENCE_MISSING",
        "budgetExpectedCostNotWorse": "ALERT_BUDGET_EVIDENCE_MISSING",
        "budgetFraudCaptureNotWorse": "ALERT_BUDGET_EVIDENCE_MISSING",
    }
    insufficient_reasons.extend(
        core_reason_codes[name]
        for name, state in core_checks.items()
        if state == NOT_EVALUATED and name in core_reason_codes
    )
    if segment_check["status"] == NOT_EVALUATED:
        insufficient_reasons.extend(segment_check["reasonCodes"])
    if stability_check["status"] == NOT_EVALUATED:
        insufficient_reasons.extend(stability_check["reasonCodes"])
    insufficient_reasons = list(dict.fromkeys(insufficient_reasons))

    failed_core = [name for name, state in core_checks.items() if state == FAIL]
    failed_soft = [
        name
        for name, assessment in (
            ("segmentRegression", segment_check),
            ("stabilityRegression", stability_check),
        )
        if assessment["status"] == FAIL
    ]

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

    passed_checks = [name for name, state in core_checks.items() if state == PASS]
    if segment_check["status"] == PASS:
        passed_checks.append("segmentRegression")
    if stability_check["status"] == PASS:
        passed_checks.append("stabilityRegression")
    failed_checks = failed_core + failed_soft
    not_evaluated_checks = [name for name, state in core_checks.items() if state == NOT_EVALUATED]
    if segment_check["status"] == NOT_EVALUATED:
        not_evaluated_checks.append("segmentRegression")
    if stability_check["status"] == NOT_EVALUATED:
        not_evaluated_checks.append("stabilityRegression")
    return {
        "outcome": outcome,
        "summary": summary,
        "passedChecks": passed_checks,
        "failedChecks": failed_checks,
        "notEvaluatedChecks": not_evaluated_checks,
        "insufficientEvidenceReasons": insufficient_reasons,
        "criteria": {
            **core_checks,
            "segmentRegression": segment_check["status"],
            "stabilityRegression": stability_check["status"],
        },
        "observedMetrics": {
            "currentPrAuc": current_pr_auc,
            "challengerPrAuc": challenger_pr_auc,
            "currentFalsePositiveRate": current_false_positive_rate,
            "challengerFalsePositiveRate": challenger_false_positive_rate,
            "currentAlertRate": current_alert_rate,
            "challengerAlertRate": challenger_alert_rate,
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
    reasons = _evaluation_window_reasons(current_evaluation, challenger_evaluation)
    split_metadata = challenger_evaluation.get("splitMetadata")
    if not isinstance(split_metadata, dict) or _bounded_number(split_metadata.get("testRows"), 1.0) is None:
        reasons.append("CHALLENGER_HELD_OUT_ROWS_MISSING")
    if _optional_metric(current_evaluation, "heldOutPrAuc", "prAuc") is None:
        reasons.append("CURRENT_PR_AUC_MISSING")
    if _optional_metric(challenger_evaluation, "prAuc") is None:
        reasons.append("CHALLENGER_PR_AUC_MISSING")
    return reasons


def _optional_metric(evaluation: dict[str, object], *names: str) -> float | None:
    for name in names:
        value = _bounded_number(evaluation.get(name), 0.0, 1.0)
        if value is not None:
            return value
    return None


def _bounded_metric(
        values: dict[str, object],
        name: str,
        minimum: float | None = None,
        maximum: float | None = None,
) -> float | None:
    return _bounded_number(values.get(name), minimum, maximum)


def _bounded_number(
        value: object,
        minimum: float | None = None,
        maximum: float | None = None,
) -> float | None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    number = float(value)
    if not math.isfinite(number):
        return None
    if minimum is not None and number < minimum:
        return None
    if maximum is not None and number > maximum:
        return None
    return number


def _comparison_state(left: float | None, right: float | None, passed: bool) -> str:
    if left is None or right is None:
        return NOT_EVALUATED
    return PASS if passed else FAIL


def _single_metric_state(value: float | None, passed: bool) -> str:
    if value is None:
        return NOT_EVALUATED
    return PASS if passed else FAIL


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


def _evaluation_window_reasons(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
) -> list[str]:
    current = current_evaluation.get("splitMetadata")
    challenger = challenger_evaluation.get("splitMetadata")
    if not isinstance(current, dict):
        return ["CURRENT_EVALUATION_WINDOW_MISSING"]
    if not isinstance(challenger, dict):
        return ["CHALLENGER_EVALUATION_WINDOW_MISSING"]

    current_rows = _bounded_number(current.get("testRows"), 1.0)
    challenger_rows = _bounded_number(challenger.get("testRows"), 1.0)
    current_start = current.get("testStartTimestamp")
    challenger_start = challenger.get("testStartTimestamp")
    if current_rows is None or not isinstance(current_start, str) or not current_start.strip():
        return ["CURRENT_EVALUATION_WINDOW_MISSING"]
    if challenger_rows is None or not isinstance(challenger_start, str) or not challenger_start.strip():
        return ["CHALLENGER_EVALUATION_WINDOW_MISSING"]
    if current_rows != challenger_rows or current_start != challenger_start:
        return ["EVALUATION_WINDOWS_NOT_COMPARABLE"]
    return []


def _budget_entry(evaluation: dict[str, object], alert_budget: float) -> dict[str, object] | None:
    budget_evaluation = evaluation.get("budgetEvaluation")
    if not isinstance(budget_evaluation, dict):
        return None
    budgets = budget_evaluation.get("budgets")
    if not isinstance(budgets, list):
        return None
    for entry in budgets:
        if not isinstance(entry, dict):
            continue
        configured_budget = _bounded_number(entry.get("alertBudget"), 0.0, 1.0)
        if configured_budget is None or abs(configured_budget - alert_budget) >= 0.000001:
            continue
        expected_cost = _bounded_number(entry.get("expectedCost"), 0.0)
        fraud_capture_rate = _bounded_number(entry.get("fraudCaptureRate"), 0.0, 1.0)
        if expected_cost is None or fraud_capture_rate is None:
            return None
        return {
            "alertBudget": configured_budget,
            "expectedCost": expected_cost,
            "fraudCaptureRate": fraud_capture_rate,
        }
    return None


def _segment_regression_check(
        current_evaluation: dict[str, object],
        challenger_evaluation: dict[str, object],
        thresholds: ChallengerComparisonThresholds,
) -> dict[str, object]:
    current_segments = current_evaluation.get("segmentEvaluation")
    challenger_segments = challenger_evaluation.get("segmentEvaluation")
    if not isinstance(current_segments, dict) or not current_segments \
            or not isinstance(challenger_segments, dict) or not challenger_segments:
        return {
            "status": NOT_EVALUATED,
            "reasonCodes": ["SEGMENT_EVALUATION_MISSING"],
            "regressions": [],
        }
    if set(current_segments) != set(challenger_segments):
        return {
            "status": NOT_EVALUATED,
            "reasonCodes": ["SEGMENT_DIMENSION_COVERAGE_MISMATCH"],
            "regressions": [],
        }
    regressions = []
    for dimension, current_by_segment in current_segments.items():
        challenger_by_segment = challenger_segments.get(dimension)
        if not isinstance(current_by_segment, dict) or not current_by_segment \
                or not isinstance(challenger_by_segment, dict) or not challenger_by_segment \
                or set(current_by_segment) != set(challenger_by_segment):
            return {
                "status": NOT_EVALUATED,
                "reasonCodes": ["SEGMENT_VALUE_COVERAGE_MISMATCH"],
                "regressions": [],
            }
        for segment, current_metrics in current_by_segment.items():
            challenger_metrics = challenger_by_segment.get(segment)
            if not isinstance(current_metrics, dict) or not isinstance(challenger_metrics, dict):
                return {
                    "status": NOT_EVALUATED,
                    "reasonCodes": ["SEGMENT_METRICS_MISSING"],
                    "regressions": [],
                }
            current_pr_auc = _bounded_metric(current_metrics, "prAuc", 0.0, 1.0)
            challenger_pr_auc = _bounded_metric(challenger_metrics, "prAuc", 0.0, 1.0)
            if current_pr_auc is None or challenger_pr_auc is None:
                return {
                    "status": NOT_EVALUATED,
                    "reasonCodes": ["SEGMENT_PR_AUC_INVALID"],
                    "regressions": [],
                }
            drop = current_pr_auc - challenger_pr_auc
            if drop > thresholds.max_segment_pr_auc_drop:
                regressions.append({"dimension": dimension, "segment": segment, "prAucDrop": round(drop, 6)})
    return {
        "status": FAIL if regressions else PASS,
        "reasonCodes": [],
        "regressions": regressions,
    }


def _stability_check(
        evaluation: dict[str, object],
        thresholds: ChallengerComparisonThresholds,
) -> dict[str, object]:
    stability = evaluation.get("stabilityAssessment")
    if not isinstance(stability, dict):
        return {
            "status": NOT_EVALUATED,
            "reasonCodes": ["STABILITY_ASSESSMENT_MISSING"],
            "failures": [],
            "metrics": None,
        }
    pr_auc_delta = _bounded_number(stability.get("prAucDelta"), -1.0, 1.0)
    expected_cost_delta = _bounded_number(stability.get("expectedCostDelta"))
    if pr_auc_delta is None or expected_cost_delta is None:
        return {
            "status": NOT_EVALUATED,
            "reasonCodes": ["STABILITY_METRICS_INCOMPLETE_OR_INVALID"],
            "failures": [],
            "metrics": None,
        }
    failures = []
    if pr_auc_delta > thresholds.max_out_of_time_pr_auc_drop:
        failures.append("prAucDelta")
    if expected_cost_delta > thresholds.max_out_of_time_cost_increase:
        failures.append("expectedCostDelta")
    return {
        "status": FAIL if failures else PASS,
        "reasonCodes": [],
        "failures": failures,
        "metrics": {
            "prAucDelta": pr_auc_delta,
            "expectedCostDelta": expected_cost_delta,
        },
    }


def _optimal(evaluation: dict[str, object]) -> dict[str, object]:
    deployed = evaluation.get("deployedAlertThresholdMetrics")
    if isinstance(deployed, dict):
        return deployed
    value = evaluation.get("optimalThreshold")
    return value if isinstance(value, dict) else {}


def _cost(evaluation: dict[str, object]) -> float | None:
    cost_evaluation = evaluation.get("costEvaluation")
    if not isinstance(cost_evaluation, dict):
        return None
    optimal = cost_evaluation.get("optimalCostThreshold")
    if not isinstance(optimal, dict):
        return None
    return _bounded_number(optimal.get("totalCost"), 0.0)
