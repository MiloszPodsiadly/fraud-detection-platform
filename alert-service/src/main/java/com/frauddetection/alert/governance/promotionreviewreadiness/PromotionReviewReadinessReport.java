package com.frauddetection.alert.governance.promotionreviewreadiness;

import java.util.List;

public record PromotionReviewReadinessReport(
        String reportType,
        String reportVersion,
        String generatedAt,
        String governanceStatus,
        String readinessStatus,
        boolean diagnosticOnly,
        boolean notPromotionApproval,
        boolean notThresholdRecommendation,
        boolean notProductionDecisioning,
        boolean notPaymentAuthorization,
        boolean notAutomaticDecisioning,
        boolean notAnalystRecommendation,
        PromotionReviewReadinessInputs inputs,
        PromotionReviewReadinessCheckInputs checkInputs,
        List<PromotionReviewReadinessCheck> checks,
        List<String> reasonCodes,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    public record PromotionReviewReadinessInputs(
            ShadowPerformanceSummaryInput shadowPerformanceSummary,
            int minimumDiagnosticEvidenceRecords,
            int recordsEvaluated
    ) {
    }

    public record ShadowPerformanceSummaryInput(
            boolean present,
            String reportType,
            String summaryVersion,
            String generatedAt
    ) {
    }

    public record PromotionReviewReadinessCheckInputs(
            String sourceShadowSummaryManifestSha256,
            ShadowPerformanceSummaryCheckInput shadowPerformanceSummary,
            PromotionReadinessGovernanceCheckInput governance,
            PromotionReadinessEvaluationCheckInput evaluation,
            String metricBasis,
            int minimumDiagnosticEvidenceRecords,
            int recordsEvaluated,
            PromotionReadinessMetricsCheckInput metrics
    ) {
    }

    public record ShadowPerformanceSummaryCheckInput(
            boolean present,
            String reportType,
            String summaryVersion,
            String generatedAt,
            String sourceEvaluationCardManifestSha256
    ) {
    }

    public record PromotionReadinessGovernanceCheckInput(
            String governanceStatus,
            boolean diagnosticOnly,
            boolean notProductionApproval,
            boolean notPromotionApproval,
            boolean notThresholdRecommendation,
            boolean notPaymentAuthorization,
            boolean notAutomaticDecisioning
    ) {
    }

    public record PromotionReadinessEvaluationCheckInput(
            String evaluationCardType,
            String evaluationCardVersion,
            String evaluationReportType
    ) {
    }

    public record PromotionReadinessMetricsCheckInput(
            PromotionReadinessMetricCheckInput alertRecommendedPrecision,
            PromotionReadinessMetricCheckInput alertRecommendedRecall,
            PromotionReadinessMetricCheckInput falsePositiveRate,
            PromotionReadinessMetricCheckInput falseNegativeRate
    ) {
    }

    public record PromotionReadinessMetricCheckInput(
            Boolean available,
            Double value,
            String reason
    ) {
    }

    public record PromotionReviewReadinessCheck(
            String name,
            String status,
            String severity
    ) {
    }
}
