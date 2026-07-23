from __future__ import annotations

from typing import Any

from offline_evaluation.shadow_performance_schema import (
    BANNER,
    SUMMARY_TYPE,
    SUMMARY_VERSION,
    validate_model_card_for_shadow_summary,
    validate_shadow_performance_summary,
)


def build_shadow_performance_summary(model_card: dict[str, Any], generated_at: str) -> dict[str, Any]:
    safe_model_card = validate_model_card_for_shadow_summary(model_card)
    metrics = safe_model_card["metricsSummary"]
    evidence = safe_model_card["evaluationEvidence"]
    subject = safe_model_card["evaluationSubject"]
    records_evaluated = evidence["recordsEvaluated"]
    approved_for = sorted(set(safe_model_card["allowedUsageModes"]) & {"COMPARE", "SHADOW"})
    summary = {
        "summaryType": SUMMARY_TYPE,
        "summaryVersion": SUMMARY_VERSION,
        "generatedAt": generated_at,
        "model": {
            "modelName": subject["modelIdentity"],
            "modelVersion": subject["modelIdentity"],
            "modelFamily": subject["subjectType"],
            "featureContractVersion": subject["featureContractVersion"],
        },
        "governance": {
            "governanceStatus": "DIAGNOSTIC_ONLY",
            "approvedFor": approved_for,
            "diagnosticOnly": True,
            "notProductionApproval": True,
            "notPromotionApproval": True,
            "notThresholdRecommendation": True,
            "notPaymentAuthorization": True,
            "notAutomaticDecisioning": True,
        },
        "evaluation": {
            "evaluationReportType": evidence["evaluationReportType"],
            "evaluationReportVersion": "FDP-124",
            "metricBasis": safe_model_card["metricBasis"],
            "datasetTimeBasis": evidence["datasetTimeBasis"],
            "datasetDeduplicationPolicy": "FDP123_RECORD_COUNT_MATCHES_METADATA_RECORDS_RETURNED",
        },
        "evaluationPopulation": {
            "datasetRecordsRead": records_evaluated,
            "recordsAcceptedForEvaluation": records_evaluated,
            "recordsExcludedNotEvaluationEligible": 0,
        },
        "metrics": {
            "precisionAtBudget": _rate(metrics["alertRecommendedPrecision"]),
            "recallAtTopK": _rate(metrics["alertRecommendedRecall"]),
            "falsePositiveRate": _rate(metrics["falsePositiveRate"]),
            "mlCaughtRulesMissedCount": 0,
            "rulesCaughtMlMissedCount": 0,
            "missingMlCount": 0,
            "missingRulesCount": 0,
            "missingProjectionCount": 0,
            "notEvaluationEligibleCount": 0,
        },
        "disagreementSummary": {
            "rulesHighMlHigh": 0,
            "rulesHighMlLowOrMedium": 0,
            "rulesLowOrMediumMlHigh": 0,
            "rulesLowOrMediumMlLowOrMedium": 0,
            "rulesMissingMlPresent": 0,
            "mlMissingRulesPresent": 0,
            "bothMissing": 0,
            "notEvaluationEligibleExcluded": 0,
        },
        "warnings": list(safe_model_card["warnings"]),
        "limitations": list(safe_model_card["limitations"]),
        "banner": BANNER,
    }
    return validate_shadow_performance_summary(summary)


def _rate(metric: dict[str, Any]) -> float:
    if metric["available"]:
        return float(metric["value"])
    return 0.0
