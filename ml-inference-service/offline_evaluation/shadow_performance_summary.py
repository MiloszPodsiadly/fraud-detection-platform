from __future__ import annotations

from typing import Any

from offline_evaluation.shadow_performance_schema import (
    BANNER,
    EXPECTED_EVALUATION_REPORT_VERSION,
    EXPECTED_GOVERNANCE_STATUS,
    REPORT_TYPE,
    SUMMARY_VERSION,
    validate_evaluation_card_for_shadow_summary,
    validate_shadow_performance_summary,
)


def build_shadow_performance_summary(
        evaluation_card: dict[str, Any],
        generated_at: str,
        *,
        source_evaluation_card_manifest_sha256: str,
) -> dict[str, Any]:
    safe_card = validate_evaluation_card_for_shadow_summary(evaluation_card)
    metrics = safe_card["metricsSummary"]
    evidence = safe_card["evaluationEvidence"]
    summary = {
        "reportType": REPORT_TYPE,
        "summaryVersion": SUMMARY_VERSION,
        "generatedAt": generated_at,
        "evaluationSubject": dict(safe_card["evaluationSubject"]),
        "metricBasis": safe_card["metricBasis"],
        "governance": {
            "governanceStatus": EXPECTED_GOVERNANCE_STATUS,
            "diagnosticOnly": True,
            "notProductionApproval": True,
            "notPromotionApproval": True,
            "notThresholdRecommendation": True,
            "notPaymentAuthorization": True,
            "notAutomaticDecisioning": True,
        },
        "evaluation": {
            "evaluationCardType": safe_card["cardType"],
            "evaluationCardVersion": safe_card["cardVersion"],
            "evaluationPurpose": safe_card["evaluationPurpose"],
            "evaluationReportType": evidence["evaluationReportType"],
            "evaluationReportVersion": EXPECTED_EVALUATION_REPORT_VERSION,
            "evaluationReportGeneratedAt": evidence["evaluationGeneratedAt"],
            "evaluationCardGeneratedAt": safe_card["generatedAt"],
            "evaluationArtifactSetVersion": evidence["evaluationArtifactSetVersion"],
            "datasetVersion": evidence["datasetVersion"],
            "datasetTimeBasis": evidence["datasetTimeBasis"],
            "sourceManifestSha256": evidence["sourceManifestSha256"],
            "sourceEvaluationCardManifestSha256": source_evaluation_card_manifest_sha256,
        },
        "evaluationPopulation": {
            "recordsEvaluated": evidence["recordsEvaluated"],
            "positiveClassCount": evidence["positiveClassCount"],
            "negativeClassCount": evidence["negativeClassCount"],
        },
        "metrics": {
            "alertRecommendedPrecision": dict(metrics["alertRecommendedPrecision"]),
            "alertRecommendedRecall": dict(metrics["alertRecommendedRecall"]),
            "falsePositiveRate": dict(metrics["falsePositiveRate"]),
            "falseNegativeRate": dict(metrics["falseNegativeRate"]),
        },
        "warnings": list(safe_card["warnings"]),
        "limitations": list(safe_card["limitations"]),
        "banner": BANNER,
    }
    return validate_shadow_performance_summary(summary)
