package com.frauddetection.alert.engineintelligence;

import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;

public record EngineIntelligenceWarningProjection(
        EngineIntelligenceWarningCode warningCode,
        int count
) {
}
