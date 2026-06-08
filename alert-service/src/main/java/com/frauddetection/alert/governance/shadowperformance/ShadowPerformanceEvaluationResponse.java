package com.frauddetection.alert.governance.shadowperformance;

public record ShadowPerformanceEvaluationResponse(
        String evaluationReportType,
        String evaluationReportVersion,
        String metricBasis,
        String datasetTimeBasis,
        String datasetDeduplicationPolicy
) {
    static ShadowPerformanceEvaluationResponse from(ShadowPerformanceSummary.ShadowPerformanceEvaluation evaluation) {
        return new ShadowPerformanceEvaluationResponse(
                evaluation.evaluationReportType(),
                evaluation.evaluationReportVersion(),
                evaluation.metricBasis(),
                evaluation.datasetTimeBasis(),
                evaluation.datasetDeduplicationPolicy()
        );
    }
}
