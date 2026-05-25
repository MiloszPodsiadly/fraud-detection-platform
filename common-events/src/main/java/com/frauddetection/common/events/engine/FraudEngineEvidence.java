package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudEngineEvidence(
        FraudEngineEvidenceType evidenceType,
        String reasonCode,
        String title,
        String description,
        String source,
        FraudEngineEvidenceStatus status
) {
    private static final int MAX_TEXT_LENGTH = 256;

    public FraudEngineEvidence {
        Objects.requireNonNull(evidenceType, "evidenceType is required");
        FraudEngineValuePolicy.validateOptionalReasonCode(reasonCode, "reasonCode");
        FraudEngineValuePolicy.requireSafeSummary(title, "title", MAX_TEXT_LENGTH);
        FraudEngineValuePolicy.validateOptionalSafeSummary(description, "description", MAX_TEXT_LENGTH);
        FraudEngineValuePolicy.requireText(source, "source", MAX_TEXT_LENGTH);
        Objects.requireNonNull(status, "status is required");
    }
}
