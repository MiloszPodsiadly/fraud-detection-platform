from __future__ import annotations

from offline_evaluation.dataset_schema import DatasetRecord, engine_risk_category, ml_ranking_score


def build_quality_metrics(records: tuple[DatasetRecord, ...] | list[DatasetRecord], review_budget: int, top_k: int) -> dict[str, float | int | str]:
    if review_budget < 1 or review_budget > 500:
        raise ValueError("reviewBudget must be between 1 and 500")
    if top_k < 1 or top_k > 500:
        raise ValueError("topK must be between 1 and 500")
    eligible = [record for record in records if record.is_evaluation_eligible]
    ranked = sorted(
        (record for record in eligible if not record.ml_signal_missing),
        key=lambda record: (-ml_ranking_score(record), record.evaluation_record_id),
    )
    budget_slice = ranked[: min(review_budget, len(ranked))]
    top_k_slice = ranked[: min(top_k, len(ranked))]
    positives = [record for record in eligible if record.is_evaluation_positive]
    negatives = [record for record in eligible if record.is_evaluation_negative]
    high_ml_negatives = [
        record for record in negatives
        if engine_risk_category(record.ml_engine_status, record.ml_risk_level, record.ml_score_bucket) == "high"
    ]
    return {
        "metricBasis": "bucket_ordered_offline_diagnostic",
        "precisionAtBudget": _share(sum(1 for record in budget_slice if record.is_evaluation_positive), len(budget_slice)),
        "recallAtTopK": _share(sum(1 for record in top_k_slice if record.is_evaluation_positive), len(positives)),
        "falsePositiveRate": _share(len(high_ml_negatives), len(negatives)),
        "mlCaughtRulesMissedCount": sum(
            1 for record in positives
            if engine_risk_category(record.ml_engine_status, record.ml_risk_level, record.ml_score_bucket) == "high"
            and engine_risk_category(record.rules_engine_status, record.rules_risk_level, record.rules_score_bucket) != "high"
        ),
        "rulesCaughtMlMissedCount": sum(
            1 for record in positives
            if engine_risk_category(record.rules_engine_status, record.rules_risk_level, record.rules_score_bucket) == "high"
            and engine_risk_category(record.ml_engine_status, record.ml_risk_level, record.ml_score_bucket) != "high"
        ),
        "missingMlCount": sum(1 for record in records if record.ml_signal_missing),
        "missingRulesCount": sum(1 for record in records if record.rules_signal_missing),
        "notEvaluationEligibleCount": sum(1 for record in records if not record.is_evaluation_eligible),
    }


def ranked_evaluation_record_ids(records: tuple[DatasetRecord, ...] | list[DatasetRecord]) -> list[str]:
    return [
        record.evaluation_record_id for record in sorted(
            (record for record in records if record.is_evaluation_eligible and not record.ml_signal_missing),
            key=lambda record: (-ml_ranking_score(record), record.evaluation_record_id),
        )
    ]


def _share(numerator: int, denominator: int) -> float:
    if denominator == 0:
        return 0.0
    return round(numerator / denominator, 6)
