package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

public record ShadowPerformanceSummary(
        String summaryType,
        String summaryVersion,
        String generatedAt,
        ShadowPerformanceModel model,
        ShadowPerformanceGovernance governance,
        ShadowPerformanceEvaluation evaluation,
        ShadowPerformancePopulation evaluationPopulation,
        ShadowPerformanceMetrics metrics,
        ShadowPerformanceDisagreement disagreementSummary,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    public record ShadowPerformanceModel(
            String modelName,
            String modelVersion,
            String modelFamily,
            String featureContractVersion
    ) {
    }

    public record ShadowPerformanceGovernance(
            String governanceStatus,
            List<String> approvedFor,
            boolean diagnosticOnly,
            boolean notProductionApproval,
            boolean notPromotionApproval,
            boolean notThresholdRecommendation,
            boolean notPaymentAuthorization,
            boolean notAutomaticDecisioning
    ) {
    }

    public record ShadowPerformanceEvaluation(
            String evaluationReportType,
            String evaluationReportVersion,
            String metricBasis,
            String datasetTimeBasis,
            String datasetDeduplicationPolicy
    ) {
    }

    public record ShadowPerformancePopulation(
            int datasetRecordsRead,
            int recordsAcceptedForEvaluation,
            int recordsExcludedNotEvaluationEligible
    ) {
    }

    public record ShadowPerformanceMetrics(
            double precisionAtBudget,
            double recallAtTopK,
            double falsePositiveRate,
            int mlCaughtRulesMissedCount,
            int rulesCaughtMlMissedCount,
            int missingMlCount,
            int missingRulesCount,
            int missingProjectionCount,
            int notEvaluationEligibleCount
    ) {
    }

    public record ShadowPerformanceDisagreement(
            int rulesHighMlHigh,
            int rulesHighMlLowOrMedium,
            int rulesLowOrMediumMlHigh,
            int rulesLowOrMediumMlLowOrMedium,
            int rulesMissingMlPresent,
            int mlMissingRulesPresent,
            int bothMissing,
            int notEvaluationEligibleExcluded
    ) {
    }
}
