package com.frauddetection.alert.api;

import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningCode;
import com.frauddetection.common.events.intelligence.EngineIntelligenceWarningSummary;

public record EngineIntelligenceWarningResponse(
        EngineIntelligenceWarningCode warningCode,
        int count
) {
    public EngineIntelligenceWarningResponse {
        EngineIntelligenceWarningSummary summary = new EngineIntelligenceWarningSummary(warningCode, count);
        warningCode = summary.code();
        count = summary.count();
    }
}
