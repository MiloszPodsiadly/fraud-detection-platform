package com.frauddetection.alert.governance.promotionreviewreadiness;

import java.util.List;

public record PromotionReviewReadinessReportResponse(
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
        PromotionReviewReadinessReport.PromotionReviewReadinessInputs inputs,
        List<PromotionReviewReadinessReport.PromotionReviewReadinessCheck> checks,
        List<String> reasonCodes,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    static PromotionReviewReadinessReportResponse from(PromotionReviewReadinessReport report) {
        return new PromotionReviewReadinessReportResponse(
                report.reportType(),
                report.reportVersion(),
                report.generatedAt(),
                report.governanceStatus(),
                report.readinessStatus(),
                report.diagnosticOnly(),
                report.notPromotionApproval(),
                report.notThresholdRecommendation(),
                report.notProductionDecisioning(),
                report.notPaymentAuthorization(),
                report.notAutomaticDecisioning(),
                report.notAnalystRecommendation(),
                report.inputs(),
                List.copyOf(report.checks()),
                List.copyOf(report.reasonCodes()),
                List.copyOf(report.warnings()),
                List.copyOf(report.limitations()),
                report.banner()
        );
    }
}
