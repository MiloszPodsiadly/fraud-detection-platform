package com.frauddetection.alert.governance.promotionreviewreadiness;

import java.util.List;

final class PromotionReviewReadinessReportTestFixtures {

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
                                "SHADOW_PERFORMANCE_SUMMARY_V1",
                                "1.0",
                                "2026-06-08T02:00:00Z"
                        ),
                        1,
                        3
                ),
                List.of(
                        check("CURRENT_SUMMARY_PRESENT"),
                        check("CURRENT_SUMMARY_VERSION_SUPPORTED"),
                        check("MODEL_CARD_PRESENT"),
                        check("MODEL_CARD_VERSION_SUPPORTED"),
                        check("GOVERNANCE_STATUS_DIAGNOSTIC_ONLY"),
                        check("GOVERNANCE_MODES_COMPARE_AND_SHADOW"),
                        check("NOT_PRODUCTION_APPROVAL_TRUE"),
                        check("NOT_PROMOTION_APPROVAL_TRUE"),
                        check("NOT_THRESHOLD_RECOMMENDATION_TRUE"),
                        check("NOT_PAYMENT_AUTHORIZATION_TRUE"),
                        check("NOT_AUTOMATIC_DECISIONING_TRUE"),
                        check("EVALUATION_REPORT_TYPE_SUPPORTED"),
                        check("METRIC_BASIS_SUPPORTED"),
                        check("DATASET_TIME_BASIS_SUPPORTED"),
                        check("DEDUPLICATION_POLICY_SUPPORTED"),
                        check("MINIMUM_DIAGNOSTIC_EVIDENCE_RECORDS"),
                        check("METRICS_PRESENT"),
                        check("DISAGREEMENT_SUMMARY_PRESENT"),
                        check("WARNINGS_PRESENT"),
                        check("LIMITATIONS_PRESENT")
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
}
