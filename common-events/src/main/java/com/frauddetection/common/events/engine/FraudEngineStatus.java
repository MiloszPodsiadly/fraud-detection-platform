package com.frauddetection.common.events.engine;

public enum FraudEngineStatus {
    AVAILABLE,
    UNAVAILABLE,
    DEGRADED,
    TIMEOUT,
    FALLBACK_USED,
    SKIPPED
}
