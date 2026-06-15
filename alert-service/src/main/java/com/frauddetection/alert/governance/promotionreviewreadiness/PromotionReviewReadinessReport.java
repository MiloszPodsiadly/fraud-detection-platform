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
        List<PromotionReviewReadinessCheck> checks,
        List<String> reasonCodes,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    public record PromotionReviewReadinessInputs(
            ShadowPerformanceSummaryInput shadowPerformanceSummary,
            int minimumDiagnosticEvidenceRecords,
            int recordsAcceptedForEvaluation
    ) {
    }

    public record ShadowPerformanceSummaryInput(
            boolean present,
            String summaryType,
            String summaryVersion,
            String generatedAt
    ) {
    }

    public record PromotionReviewReadinessCheck(
            String name,
            String status,
            String severity
    ) {
    }
}
