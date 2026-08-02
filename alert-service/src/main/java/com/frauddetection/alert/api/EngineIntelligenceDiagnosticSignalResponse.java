package com.frauddetection.alert.api;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceDiagnosticSignal;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;

import java.util.Objects;

public record EngineIntelligenceDiagnosticSignalResponse(
        String engineId,
        FraudEngineType engineType,
        EngineIntelligenceEngineStatusResponse engineStatus,
        EngineIntelligenceSignalCategory signalCategory,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        String reasonCode
) {
    public EngineIntelligenceDiagnosticSignalResponse {
        Objects.requireNonNull(engineStatus, "engineStatus is required");
        EngineIntelligenceDiagnosticSignal signal = new EngineIntelligenceDiagnosticSignal(
                engineId,
                engineType,
                contractStatus(engineStatus),
                signalCategory,
                riskLevel,
                scoreBucket,
                reasonCode
        );
        engineId = signal.engineId();
        engineType = signal.engineType();
        signalCategory = signal.signalCategory();
        riskLevel = signal.riskLevel();
        scoreBucket = signal.scoreBucket();
        reasonCode = signal.reasonCode();
    }

    private static FraudEngineStatus contractStatus(EngineIntelligenceEngineStatusResponse status) {
        return switch (status) {
            case AVAILABLE -> FraudEngineStatus.AVAILABLE;
            case TIMEOUT -> FraudEngineStatus.TIMEOUT;
            case DEGRADED -> FraudEngineStatus.DEGRADED;
            case NOT_APPLICABLE -> FraudEngineStatus.SKIPPED;
            case UNAVAILABLE -> FraudEngineStatus.UNAVAILABLE;
        };
    }
}
