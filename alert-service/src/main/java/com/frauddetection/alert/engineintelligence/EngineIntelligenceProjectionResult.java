package com.frauddetection.alert.engineintelligence;

import java.util.Optional;

public record EngineIntelligenceProjectionResult(
        Optional<EngineIntelligenceProjection> projection,
        Optional<EngineIntelligenceProjectionOmissionReason> omissionReason
) {
    public static EngineIntelligenceProjectionResult projected(EngineIntelligenceProjection projection) {
        return new EngineIntelligenceProjectionResult(Optional.of(projection), Optional.empty());
    }

    public static EngineIntelligenceProjectionResult omitted(EngineIntelligenceProjectionOmissionReason reason) {
        return new EngineIntelligenceProjectionResult(Optional.empty(), Optional.of(reason));
    }
}
