package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineIntelligenceComparison(
        EngineIntelligenceAgreementStatus agreementStatus,
        EngineIntelligenceRiskMismatchStatus riskMismatchStatus,
        EngineIntelligenceScoreDeltaBucket scoreDeltaBucket
) {
    public EngineIntelligenceComparison {
        Objects.requireNonNull(agreementStatus, "agreementStatus is required");
        Objects.requireNonNull(riskMismatchStatus, "riskMismatchStatus is required");
        Objects.requireNonNull(scoreDeltaBucket, "scoreDeltaBucket is required");
    }
}
