package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;
import java.util.Optional;

public class EngineIntelligenceEmissionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceEmissionService.class);

    private final EngineIntelligenceEmissionProperties properties;
    private final ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> diagnosticPipeline;

    public EngineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> diagnosticPipeline
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.diagnosticPipeline = Objects.requireNonNull(diagnosticPipeline, "diagnosticPipeline is required");
    }

    public Optional<EngineIntelligenceSummary> emitIfEnabled(FraudScoringRequest scoringRequest) {
        Objects.requireNonNull(scoringRequest, "scoringRequest is required");
        if (!properties.emitEnabled()) {
            return Optional.empty();
        }
        try {
            EngineIntelligenceDiagnosticEnrichmentPipeline pipeline = diagnosticPipeline.getIfAvailable();
            if (pipeline == null) {
                log.warn("Engine intelligence enrichment omitted.");
                return Optional.empty();
            }
            return pipeline.enrich(scoringRequest);
        } catch (RuntimeException exception) {
            log.warn("Engine intelligence enrichment omitted.");
            return Optional.empty();
        }
    }

    public boolean emitEnabled() {
        return properties.emitEnabled();
    }
}
