package com.frauddetection.alert.engineintelligence;

import java.util.Objects;

public final class EngineIntelligenceProjectionValidationException extends IllegalArgumentException {

    private final EngineIntelligenceProjectionOmissionReason reason;

    EngineIntelligenceProjectionValidationException(EngineIntelligenceProjectionOmissionReason reason) {
        super(Objects.requireNonNull(reason, "reason is required").name());
        this.reason = reason;
    }

    EngineIntelligenceProjectionOmissionReason reason() {
        return reason;
    }
}
