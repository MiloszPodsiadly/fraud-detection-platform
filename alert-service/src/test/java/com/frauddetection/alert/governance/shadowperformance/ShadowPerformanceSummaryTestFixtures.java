package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

final class ShadowPerformanceSummaryTestFixtures {

    private ShadowPerformanceSummaryTestFixtures() {
    }

    static ShadowPerformanceSummary validSummary() {
        return new ShadowPerformanceSummary(
                "SHADOW_PERFORMANCE_SUMMARY_V1",
                "1.0",
                "2026-06-08T02:00:00Z",
                new ShadowPerformanceSummary.ShadowPerformanceModel(
                        "python-logistic-fraud-model",
                        "2026-04-21.trained.v1",
                        "LOGISTIC_REGRESSION",
                        "2026-04-22.v1"
                ),
                new ShadowPerformanceSummary.ShadowPerformanceGovernance(
                        "DIAGNOSTIC_ONLY",
                        List.of("COMPARE", "SHADOW"),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                ),
                new ShadowPerformanceSummary.ShadowPerformanceEvaluation(
                        "PYTHON_ML_EVALUATION_FOUNDATION",
                        "FDP-103",
                        "bucket_ordered_offline_diagnostic",
                        "FEEDBACK_SUBMITTED_AT",
                        "TRANSACTION_REFERENCE_NEWEST_SUBMITTED_AT_FEEDBACK_ID_ASC"
                ),
                new ShadowPerformanceSummary.ShadowPerformancePopulation(5, 3, 1),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        0.666667,
                        0.5,
                        0.25,
                        1,
                        1,
                        1,
                        1,
                        1,
                        1
                ),
                new ShadowPerformanceSummary.ShadowPerformanceDisagreement(1, 0, 1, 1, 0, 1, 0, 1),
                List.of("MISSING_ML_SIGNAL_PRESENT", "MISSING_PROJECTION_PRESENT", "MISSING_RULES_SIGNAL_PRESENT"),
                List.of(
                        "ANALYST_LABELS_ARE_EVALUATION_SIGNALS_NOT_GROUND_TRUTH",
                        "BUCKET_ORDERED_METRICS_NOT_CALIBRATED_PROBABILITIES",
                        "DIAGNOSTIC_ONLY",
                        "NOT_EVALUATION_ELIGIBLE_EXCLUDED_FROM_QUALITY_METRICS",
                        "NO_AUTOMATIC_APPROVE_DECLINE_BLOCK",
                        "NO_MODEL_PROMOTION_APPROVAL",
                        "NO_PAYMENT_AUTHORIZATION",
                        "NO_PRODUCTION_DECISIONING_APPROVAL",
                        "NO_THRESHOLD_RECOMMENDATION",
                        "OFFLINE_ONLY"
                ),
                ShadowPerformanceSummaryContract.REQUIRED_BANNER
        );
    }
}
