package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

public record ShadowPerformanceSummary(
        String reportType,
        String summaryVersion,
        String generatedAt,
        EvaluationSubject evaluationSubject,
        String metricBasis,
        ShadowPerformanceGovernance governance,
        ShadowPerformanceEvaluation evaluation,
        ShadowPerformancePopulation evaluationPopulation,
        ShadowPerformanceMetrics metrics,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    public record EvaluationSubject(
            String subjectType,
            String sourceComponent,
            String sourceVersion,
            String featureContractVersion,
            String modelIdentity,
            String modelArtifactSha256,
            String identityCompleteness
    ) {
    }

    public record ShadowPerformanceGovernance(
            String governanceStatus,
            boolean diagnosticOnly,
            boolean notProductionApproval,
            boolean notPromotionApproval,
            boolean notThresholdRecommendation,
            boolean notPaymentAuthorization,
            boolean notAutomaticDecisioning
    ) {
    }

    public record ShadowPerformanceEvaluation(
            String evaluationCardType,
            String evaluationCardVersion,
            String evaluationPurpose,
            String evaluationReportType,
            String evaluationReportVersion,
            String evaluationReportGeneratedAt,
            String evaluationCardGeneratedAt,
            String evaluationArtifactSetVersion,
            String datasetVersion,
            String datasetTimeBasis,
            String sourceManifestSha256,
            String sourceEvaluationCardManifestSha256
    ) {
    }

    public record ShadowPerformancePopulation(
            int recordsEvaluated,
            int positiveClassCount,
            int negativeClassCount
    ) {
    }

    public record ShadowPerformanceMetrics(
            MetricValue alertRecommendedPrecision,
            MetricValue alertRecommendedRecall,
            MetricValue falsePositiveRate,
            MetricValue falseNegativeRate
    ) {
    }

    public record MetricValue(
            Boolean available,
            Double value,
            String reason
    ) {
    }
}
