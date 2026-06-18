package com.frauddetection.alert.api;

import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;

public record EngineIntelligenceWarningResponse(
        EngineIntelligenceWarningCode warningCode,
        int count
) {
}
