package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

final class ShadowPerformanceSummaryTestFixtures {

    private ShadowPerformanceSummaryTestFixtures() {
    }

    static ShadowPerformanceSummary validSummary() {
        return new ShadowPerformanceSummary(
                "SHADOW_PERFORMANCE_SUMMARY_V2",
                "shadow-performance-summary-v2",
                "2026-06-08T02:00:00Z",
                new ShadowPerformanceSummary.EvaluationSubject(
                        "PLATFORM_RECOMMENDATION",
                        "ENGINE_INTELLIGENCE_PROJECTION",
                        "ENGINE_INTELLIGENCE_PROJECTION_V1",
                        "NOT_APPLICABLE",
                        "NOT_AVAILABLE",
                        "NOT_AVAILABLE",
                        "NO_MODEL_ARTIFACT_IDENTITY_IN_FDP123_SOURCE"
                ),
                "ALERT_RECOMMENDED_VS_BOUNDED_ANALYST_FEEDBACK",
                new ShadowPerformanceSummary.ShadowPerformanceGovernance(
                        "DIAGNOSTIC_ONLY",
                        true,
                        true,
                        true,
                        true,
                        true,
                        true
                ),
                new ShadowPerformanceSummary.ShadowPerformanceEvaluation(
                        "PLATFORM_RECOMMENDATION_EVALUATION_CARD_V1",
                        "platform-recommendation-evaluation-card-v1",
                        "OFFLINE_DIAGNOSTIC",
                        "FDP123_FEEDBACK_DATASET_OFFLINE_EVALUATION_V1",
                        "FDP-124",
                        "fdp123-report-artifact-set-v1",
                        "feedback-dataset-v1",
                        "FEEDBACK_CREATED_AT",
                        "a".repeat(64)
                ),
                new ShadowPerformanceSummary.ShadowPerformancePopulation(5, 3, 2),
                new ShadowPerformanceSummary.ShadowPerformanceMetrics(
                        metric(0.666667),
                        metric(0.5),
                        metric(0.25),
                        metric(0.2)
                ),
                List.of("LOW_SAMPLE_SIZE"),
                List.of(
                        "ANALYST_FEEDBACK_LABELS_ARE_NOT_LEGAL_GROUND_TRUTH",
                        "OFFLINE_DIAGNOSTIC_METRICS_ARE_NOT_PRODUCTION_APPROVAL",
                        "METRICS_ARE_PLATFORM_RECOMMENDATION_DIAGNOSTICS",
                        "SMALL_SAMPLE_SIZE_MAY_BE_INCONCLUSIVE",
                        "PSEUDONYMOUS_REFERENCES_ARE_NOT_ANONYMIZATION",
                        "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_APPROVE_PROMOTION",
                        "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_AUTHORIZE_AUTOMATIC_DECLINE",
                        "PLATFORM_RECOMMENDATION_EVALUATION_CARD_DOES_NOT_CHANGE_SCORING_THRESHOLDS"
                ),
                ShadowPerformanceSummaryContract.REQUIRED_BANNER
        );
    }

    static ShadowPerformanceSummary.MetricValue metric(double value) {
        return new ShadowPerformanceSummary.MetricValue(true, value, null);
    }

    static ShadowPerformanceSummary.MetricValue unavailable(String reason) {
        return new ShadowPerformanceSummary.MetricValue(false, null, reason);
    }
}
