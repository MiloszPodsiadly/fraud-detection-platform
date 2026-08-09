package com.frauddetection.alert.api;

import com.frauddetection.common.events.engine.FraudEngineStatus;

public enum EngineIntelligenceEngineStatusResponse {
    AVAILABLE,
    UNAVAILABLE,
    TIMEOUT,
    DEGRADED,
    NOT_APPLICABLE;

    FraudEngineStatus toFraudEngineStatus() {
        return switch (this) {
            case AVAILABLE -> FraudEngineStatus.AVAILABLE;
            case UNAVAILABLE -> FraudEngineStatus.UNAVAILABLE;
            case TIMEOUT -> FraudEngineStatus.TIMEOUT;
            case DEGRADED -> FraudEngineStatus.DEGRADED;
            case NOT_APPLICABLE -> FraudEngineStatus.SKIPPED;
        };
    }
}
