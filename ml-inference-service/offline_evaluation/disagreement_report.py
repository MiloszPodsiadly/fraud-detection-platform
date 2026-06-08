from __future__ import annotations

from offline_evaluation.dataset_schema import DatasetRecord, engine_risk_category


def build_disagreement_report(records: tuple[DatasetRecord, ...] | list[DatasetRecord]) -> dict[str, int]:
    report = {
        "rulesHighMlHigh": 0,
        "rulesHighMlLowOrMedium": 0,
        "rulesLowOrMediumMlHigh": 0,
        "rulesLowOrMediumMlLowOrMedium": 0,
        "rulesMissingMlPresent": 0,
        "mlMissingRulesPresent": 0,
        "bothMissing": 0,
        "notEvaluationEligibleExcluded": 0,
    }
    for record in records:
        if not record.is_evaluation_eligible:
            report["notEvaluationEligibleExcluded"] += 1
            continue
        ml = engine_risk_category(record.ml_engine_status, record.ml_risk_level, record.ml_score_bucket)
        rules = engine_risk_category(record.rules_engine_status, record.rules_risk_level, record.rules_score_bucket)
        if rules == "missing" and ml == "missing":
            report["bothMissing"] += 1
        elif rules == "missing":
            report["rulesMissingMlPresent"] += 1
        elif ml == "missing":
            report["mlMissingRulesPresent"] += 1
        elif rules == "high" and ml == "high":
            report["rulesHighMlHigh"] += 1
        elif rules == "high":
            report["rulesHighMlLowOrMedium"] += 1
        elif ml == "high":
            report["rulesLowOrMediumMlHigh"] += 1
        else:
            report["rulesLowOrMediumMlLowOrMedium"] += 1
    return report
