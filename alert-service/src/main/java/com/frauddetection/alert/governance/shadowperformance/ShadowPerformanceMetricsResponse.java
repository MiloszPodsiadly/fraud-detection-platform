package com.frauddetection.alert.governance.shadowperformance;

public record ShadowPerformanceMetricsResponse(
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
    static ShadowPerformanceMetricsResponse from(ShadowPerformanceSummary.ShadowPerformanceMetrics metrics) {
        return new ShadowPerformanceMetricsResponse(
                metrics.precisionAtBudget(),
                metrics.recallAtTopK(),
                metrics.falsePositiveRate(),
                metrics.mlCaughtRulesMissedCount(),
                metrics.rulesCaughtMlMissedCount(),
                metrics.missingMlCount(),
                metrics.missingRulesCount(),
                metrics.missingProjectionCount(),
                metrics.notEvaluationEligibleCount()
        );
    }
}
