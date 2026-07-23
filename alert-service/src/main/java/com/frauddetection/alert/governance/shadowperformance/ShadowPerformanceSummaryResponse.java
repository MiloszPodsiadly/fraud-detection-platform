package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

public record ShadowPerformanceSummaryResponse(
        String reportType,
        String summaryVersion,
        String generatedAt,
        ShadowPerformanceSummary.EvaluationSubject evaluationSubject,
        String metricBasis,
        ShadowPerformanceSummary.ShadowPerformanceGovernance governance,
        ShadowPerformanceSummary.ShadowPerformanceEvaluation evaluation,
        ShadowPerformanceSummary.ShadowPerformancePopulation evaluationPopulation,
        ShadowPerformanceSummary.ShadowPerformanceMetrics metrics,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    static ShadowPerformanceSummaryResponse from(ShadowPerformanceSummary summary) {
        return new ShadowPerformanceSummaryResponse(
                summary.reportType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                summary.evaluationSubject(),
                summary.metricBasis(),
                summary.governance(),
                summary.evaluation(),
                summary.evaluationPopulation(),
                summary.metrics(),
                List.copyOf(summary.warnings()),
                List.copyOf(summary.limitations()),
                summary.banner()
        );
    }
}
