package com.frauddetection.alert.governance.shadowperformance;

public record ShadowPerformanceModelResponse(
        String modelName,
        String modelVersion,
        String modelFamily,
        String featureContractVersion
) {
    static ShadowPerformanceModelResponse from(ShadowPerformanceSummary.ShadowPerformanceModel model) {
        return new ShadowPerformanceModelResponse(
                model.modelName(),
                model.modelVersion(),
                model.modelFamily(),
                model.featureContractVersion()
        );
    }
}
