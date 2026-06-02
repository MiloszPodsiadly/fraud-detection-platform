package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;

import java.util.List;
import java.util.Objects;

public record EngineIntelligenceEngineProjection(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus status,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        List<String> reasonCodes
) {
    public EngineIntelligenceEngineProjection {
        Objects.requireNonNull(engineId, "engineId is required");
        Objects.requireNonNull(engineType, "engineType is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(scoreBucket, "scoreBucket is required");
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
