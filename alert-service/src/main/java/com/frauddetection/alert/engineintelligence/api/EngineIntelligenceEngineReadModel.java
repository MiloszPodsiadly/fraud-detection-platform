package com.frauddetection.alert.engineintelligence.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
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
        EngineIntelligenceEngineResult result = new EngineIntelligenceEngineResult(
                engineId,
                engineType,
                status,
                riskLevel,
                scoreBucket,
                reasonCodes == null ? List.of() : reasonCodes
        );
        engineId = result.engineId();
        engineType = result.engineType();
        status = result.status();
        riskLevel = result.riskLevel();
        scoreBucket = result.scoreBucket();
        reasonCodes = result.reasonCodes();
    }
}
