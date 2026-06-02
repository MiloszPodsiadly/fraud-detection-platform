package com.frauddetection.alert.engineintelligence.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EngineIntelligenceEngineReadModel(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus status,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        List<String> reasonCodes
) {
    public EngineIntelligenceEngineReadModel {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
