package com.frauddetection.alert.api;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceEngineResult;
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
        EngineIntelligenceEngineResult result = new EngineIntelligenceEngineResult(
                engineId,
                engineType,
                contractStatus(status),
                riskLevel,
                scoreBucket,
                reasonCodes == null ? List.of() : reasonCodes
        );
        engineId = result.engineId();
        engineType = result.engineType();
        riskLevel = result.riskLevel();
        scoreBucket = result.scoreBucket();
        reasonCodes = result.reasonCodes();
    }

    private static FraudEngineStatus contractStatus(EngineIntelligenceEngineStatusResponse status) {
        if (status == null) {
            return FraudEngineStatus.UNAVAILABLE;
        }
        return switch (status) {
            case AVAILABLE -> FraudEngineStatus.AVAILABLE;
            case TIMEOUT -> FraudEngineStatus.TIMEOUT;
            case DEGRADED -> FraudEngineStatus.DEGRADED;
            case NOT_APPLICABLE -> FraudEngineStatus.SKIPPED;
            case UNAVAILABLE -> FraudEngineStatus.UNAVAILABLE;
        };
    }
}
