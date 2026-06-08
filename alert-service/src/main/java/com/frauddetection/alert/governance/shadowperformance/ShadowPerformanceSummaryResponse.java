package com.frauddetection.alert.governance.shadowperformance;

import java.util.List;

public record ShadowPerformanceSummaryResponse(
        String summaryType,
        String summaryVersion,
        String generatedAt,
        ShadowPerformanceModelResponse model,
        ShadowPerformanceGovernanceResponse governance,
        ShadowPerformanceEvaluationResponse evaluation,
        ShadowPerformancePopulationResponse evaluationPopulation,
        ShadowPerformanceMetricsResponse metrics,
        ShadowPerformanceDisagreementResponse disagreementSummary,
        List<String> warnings,
        List<String> limitations,
        String banner
) {
    static ShadowPerformanceSummaryResponse from(ShadowPerformanceSummary summary) {
        return new ShadowPerformanceSummaryResponse(
                summary.summaryType(),
                summary.summaryVersion(),
                summary.generatedAt(),
                ShadowPerformanceModelResponse.from(summary.model()),
                ShadowPerformanceGovernanceResponse.from(summary.governance()),
                ShadowPerformanceEvaluationResponse.from(summary.evaluation()),
                ShadowPerformancePopulationResponse.from(summary.evaluationPopulation()),
                ShadowPerformanceMetricsResponse.from(summary.metrics()),
                ShadowPerformanceDisagreementResponse.from(summary.disagreementSummary()),
                List.copyOf(summary.warnings()),
                List.copyOf(summary.limitations()),
                summary.banner()
        );
    }
}
