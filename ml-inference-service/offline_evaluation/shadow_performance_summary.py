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
    summary = {
        "summaryType": SUMMARY_TYPE,
        "summaryVersion": SUMMARY_VERSION,
        "generatedAt": generated_at,
        "model": {
            "modelName": safe_model_card["modelName"],
            "modelVersion": safe_model_card["modelVersion"],
            "modelFamily": safe_model_card["modelFamily"],
            "featureContractVersion": safe_model_card["featureContractVersion"],
        },
        "governance": {
            "governanceStatus": safe_model_card["governanceStatus"],
            "approvedFor": list(safe_model_card["approvedFor"]),
            "diagnosticOnly": True,
            "notProductionApproval": True,
            "notPromotionApproval": True,
            "notThresholdRecommendation": True,
            "notPaymentAuthorization": True,
            "notAutomaticDecisioning": True,
        },
        "evaluation": {
            "evaluationReportType": safe_model_card["evaluationReportType"],
            "evaluationReportVersion": safe_model_card["evaluationReportVersion"],
            "metricBasis": metrics["metricBasis"],
            "datasetTimeBasis": safe_model_card["datasetTimeBasis"],
            "datasetDeduplicationPolicy": safe_model_card["datasetDeduplicationPolicy"],
        },
        "metrics": {
            "precisionAtBudget": metrics["precisionAtBudget"],
            "recallAtTopK": metrics["recallAtTopK"],
            "falsePositiveRate": metrics["falsePositiveRate"],
            "mlCaughtRulesMissedCount": metrics["mlCaughtRulesMissedCount"],
            "rulesCaughtMlMissedCount": metrics["rulesCaughtMlMissedCount"],
            "missingMlCount": metrics["missingMlCount"],
            "missingRulesCount": metrics["missingRulesCount"],
            "missingProjectionCount": metrics["missingProjectionCount"],
            "notEvaluationEligibleCount": metrics["notEvaluationEligibleCount"],
        },
        "disagreementSummary": dict(metrics["disagreementSummary"]),
        "warnings": list(safe_model_card["warnings"]),
        "limitations": list(safe_model_card["limitations"]),
        "banner": BANNER,
    }
    return validate_shadow_performance_summary(summary)
