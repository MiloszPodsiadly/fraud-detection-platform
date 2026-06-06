package com.frauddetection.alert.engineintelligence.dataset;

import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreBucket;
import com.frauddetection.common.events.intelligence.EngineIntelligenceSignalCategory;

public record EngineIntelligenceFeedbackDatasetDiagnosticSignal(
        FraudEngineType engineType,
        EngineIntelligenceSignalCategory signalCategory,
        RiskLevel riskLevel,
        EngineIntelligenceScoreBucket scoreBucket,
        String reasonCode
) {
}
