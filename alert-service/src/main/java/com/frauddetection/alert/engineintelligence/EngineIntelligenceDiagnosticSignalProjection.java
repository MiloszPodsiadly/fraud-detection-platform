package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;

import java.util.Objects;

public record EngineIntelligenceDiagnosticSignalProjection(
        String engineId,
        FraudEngineType engineType,
        FraudEngineStatus engineStatus,
        EngineIntelligenceSignalCategory signalCategory,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        String reasonCode
) {
    public EngineIntelligenceDiagnosticSignalProjection {
        Objects.requireNonNull(engineId, "engineId is required");
        Objects.requireNonNull(engineType, "engineType is required");
        Objects.requireNonNull(engineStatus, "engineStatus is required");
        Objects.requireNonNull(signalCategory, "signalCategory is required");
        Objects.requireNonNull(scoreBucket, "scoreBucket is required");
        Objects.requireNonNull(reasonCode, "reasonCode is required");
    }
}
