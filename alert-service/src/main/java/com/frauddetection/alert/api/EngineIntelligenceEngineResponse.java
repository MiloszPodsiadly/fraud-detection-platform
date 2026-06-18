package com.frauddetection.alert.api;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;

import java.util.List;

public record EngineIntelligenceEngineResponse(
        String engineId,
        FraudEngineType engineType,
        EngineIntelligenceEngineStatusResponse status,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        List<String> reasonCodes
) {

    public EngineIntelligenceEngineResponse {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
