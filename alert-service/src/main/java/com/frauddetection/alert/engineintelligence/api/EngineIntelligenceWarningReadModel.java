package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;

public record EngineIntelligenceWarningReadModel(
        EngineIntelligenceWarningCode warningCode,
        int count
) {
}
