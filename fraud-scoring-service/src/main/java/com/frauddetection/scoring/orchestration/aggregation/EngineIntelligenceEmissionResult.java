package com.frauddetection.scoring.orchestration.aggregation;

import java.util.Objects;
import java.util.Optional;

public record EngineIntelligenceEmissionResult(
        Optional<EngineIntelligenceEnrichmentResult> enrichment,
        Optional<EngineIntelligenceEmissionOmissionReason> omissionReason
) {
    public EngineIntelligenceEmissionResult {
        enrichment = Objects.requireNonNull(enrichment, "enrichment is required");
        omissionReason = Objects.requireNonNull(omissionReason, "omissionReason is required");
        if (enrichment.isPresent() == omissionReason.isPresent()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_EMISSION_REQUIRES_EXACTLY_ONE_OUTCOME");
        }
    }

    public static EngineIntelligenceEmissionResult emitted(EngineIntelligenceEnrichmentResult enrichment) {
        return new EngineIntelligenceEmissionResult(
                Optional.of(Objects.requireNonNull(enrichment, "enrichment is required")),
                Optional.empty()
        );
    }

    public static EngineIntelligenceEmissionResult omitted(EngineIntelligenceEmissionOmissionReason reason) {
        return new EngineIntelligenceEmissionResult(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(reason, "reason is required"))
        );
    }
}
