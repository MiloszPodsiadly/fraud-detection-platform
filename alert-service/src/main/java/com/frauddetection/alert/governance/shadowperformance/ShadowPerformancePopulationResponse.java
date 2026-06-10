package com.frauddetection.alert.governance.shadowperformance;

public record ShadowPerformancePopulationResponse(
        int datasetRecordsRead,
        int recordsAcceptedForEvaluation,
        int recordsExcludedNotEvaluationEligible
) {
    static ShadowPerformancePopulationResponse from(ShadowPerformanceSummary.ShadowPerformancePopulation population) {
        return new ShadowPerformancePopulationResponse(
                population.datasetRecordsRead(),
                population.recordsAcceptedForEvaluation(),
                population.recordsExcludedNotEvaluationEligible()
        );
    }
}
