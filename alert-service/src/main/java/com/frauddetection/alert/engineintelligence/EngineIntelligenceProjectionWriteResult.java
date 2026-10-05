package com.frauddetection.alert.engineintelligence;

import java.util.Objects;

public record EngineIntelligenceProjectionWriteResult(
        Status status,
        EngineIntelligenceProjection projection
) {
    public EngineIntelligenceProjectionWriteResult {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(projection, "projection is required");
    }

    public enum Status {
        ACCEPTED,
        STALE,
        SOURCE_PAYLOAD_CONFLICT
    }
}
