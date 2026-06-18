package com.frauddetection.alert.api;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;

public record EngineIntelligenceDiagnosticSignalResponse(
        String engineId,
        FraudEngineType engineType,
        EngineIntelligenceEngineStatusResponse engineStatus,
        EngineIntelligenceSignalCategory signalCategory,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        String reasonCode
) {
}
