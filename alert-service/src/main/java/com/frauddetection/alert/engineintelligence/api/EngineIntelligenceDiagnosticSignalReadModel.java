package com.frauddetection.alert.engineintelligence.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineIntelligenceDiagnosticSignalReadModel(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus engineStatus,
        EngineIntelligenceSignalCategory signalCategory,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        String reasonCode
) {
}
