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
    public FraudEngineEvidence {
        Objects.requireNonNull(evidenceType, "evidenceType is required");
        reasonCode = FraudEngineValuePolicy.optionalMachineCode(
                reasonCode,
                "reasonCode",
                FraudEngineValuePolicy.EVIDENCE_CODE_MAX_LENGTH
        );
        title = FraudEngineValuePolicy.requireSafeSummary(
                title,
                "title",
                FraudEngineValuePolicy.DESCRIPTION_CODE_MAX_LENGTH
        );
        description = FraudEngineValuePolicy.validateOptionalSafeSummary(
                description,
                "description",
                FraudEngineValuePolicy.DESCRIPTION_CODE_MAX_LENGTH
        );
        source = FraudEngineValuePolicy.requireMachineCode(
                source,
                "source",
                FraudEngineValuePolicy.EVIDENCE_CODE_MAX_LENGTH
        );
        Objects.requireNonNull(status, "status is required");
    }

    public String evidenceCode() {
        return reasonCode;
    }

    public String descriptionCode() {
        return title;
    }
}
