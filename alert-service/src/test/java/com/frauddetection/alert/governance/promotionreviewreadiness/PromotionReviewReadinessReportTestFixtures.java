package com.frauddetection.alert.governance.promotionreviewreadiness;

import java.util.List;

final class PromotionReviewReadinessReportTestFixtures {

    private static final String SOURCE_SHADOW_MANIFEST_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SOURCE_EVALUATION_CARD_MANIFEST_SHA256 =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private PromotionReviewReadinessReportTestFixtures() {
    }

    static PromotionReviewReadinessReport validReport() {
        return new PromotionReviewReadinessReport(
                PromotionReviewReadinessReportContract.REPORT_TYPE,
                PromotionReviewReadinessReportContract.REPORT_VERSION,
                "2026-06-13T00:00:00Z",
                PromotionReviewReadinessReportContract.GOVERNANCE_STATUS,
                "REVIEWABLE",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                new PromotionReviewReadinessReport.PromotionReviewReadinessInputs(
                        new PromotionReviewReadinessReport.ShadowPerformanceSummaryInput(
                                true,
                                "SHADOW_PERFORMANCE_SUMMARY_V2",
                                "shadow-performance-summary-v2",
                                "2026-06-08T02:00:00Z"
                        ),
                        1,
                        3
                ),
                new PromotionReviewReadinessReport.PromotionReviewReadinessCheckInputs(
                        SOURCE_SHADOW_MANIFEST_SHA256,
                        new PromotionReviewReadinessReport.ShadowPerformanceSummaryCheckInput(
                                true,
                                "SHADOW_PERFORMANCE_SUMMARY_V2",
                                "shadow-performance-summary-v2",
                                "2026-06-08T02:00:00Z",
                                SOURCE_EVALUATION_CARD_MANIFEST_SHA256
                        ),
                        new PromotionReviewReadinessReport.PromotionReadinessGovernanceCheckInput(
                                PromotionReviewReadinessReportContract.GOVERNANCE_STATUS,
                                true,
                                true,
                                true,
                                true,
                                true,
                                true
                        ),
                        new PromotionReviewReadinessReport.PromotionReadinessEvaluationCheckInput(
                                "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1",
                                "platform-recommendation-evaluation-card-v1",
                                "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1"
                        ),
                        "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK",
                        1,
                        3,
                        new PromotionReviewReadinessReport.PromotionReadinessMetricsCheckInput(
                                metric(0.8d),
                                metric(0.75d),
                                metric(0.1d),
                                metric(0.2d)
                        )
                ),
                List.of(
                        check("CURRENT_SUMMARY_PRESENT"),
                        check("CURRENT_SUMMARY_VERSION_SUPPORTED"),
                        check("EVALUATION_CARD_PRESENT"),
                        check("EVALUATION_CARD_VERSION_SUPPORTED"),
                        check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY"),
                        check("NOT_PRODUCTION_APPROVAL_TRUE"),
                        check("NOT_PROMOTION_APPROVAL_TRUE"),
                        check("NOT_THRESHOLD_RECOMMENDATION_TRUE"),
                        check("NOT_PAYMENT_AUTHORIZATION_TRUE"),
                        check("NOT_AUTOMATIC_DECISIONING_TRUE"),
                        check("EVALUATION_REPORT_TYPE_SUPPORTED"),
                        check("METRIC_BASIS_SUPPORTED"),
                        check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS", "HIGH"),
                        check("ALERT_RECOMMENDED_PRECISION_AVAILABLE", "MEDIUM"),
                        check("ALERT_RECOMMENDED_RECALL_AVAILABLE", "MEDIUM"),
                        check("FALSE_POSITIVE_RATE_AVAILABLE", "MEDIUM"),
                        check("FALSE_NEGATIVE_RATE_AVAILABLE", "MEDIUM")
                ),
                List.of(),
                List.of("MISSING_ML_SIGNAL_PRESENT", "MISSING_PROJECTION_PRESENT", "MISSING_RULES_SIGNAL_PRESENT"),
                List.of(
                        "DOES_NOT_AUTHORIZE_PAYMENTS",
                        "DOES_NOT_CHANGE_SCORING",
                        "DOES_NOT_RECOMMEND_THRESHOLDS",
                        "HUMAN_REVIEW_START_ONLY",
                        "OFFLINE_DIAGNOSTIC_AID_ONLY"
                ),
                PromotionReviewReadinessReportContract.REQUIRED_BANNER
        );
    }

    private static PromotionReviewReadinessReport.PromotionReviewReadinessCheck check(String name) {
        return new PromotionReviewReadinessReport.PromotionReviewReadinessCheck(name, "PASS", "INFO");
    }

    private static PromotionReviewReadinessReport.PromotionReviewReadinessCheck check(String name, String severity) {
        return new PromotionReviewReadinessReport.PromotionReviewReadinessCheck(name, "PASS", severity);
    }

    private static PromotionReviewReadinessReport.PromotionReadinessMetricCheckInput metric(double value) {
        return new PromotionReviewReadinessReport.PromotionReadinessMetricCheckInput(true, value, null);
    }
}
