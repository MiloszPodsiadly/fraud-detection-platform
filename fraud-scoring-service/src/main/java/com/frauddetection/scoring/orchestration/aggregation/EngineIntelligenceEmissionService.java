package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.scoring.config.EngineIntelligenceEmissionProperties;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class EngineIntelligenceEmissionService {

    private static final Logger log = LoggerFactory.getLogger(EngineIntelligenceEmissionService.class);

    private final EngineIntelligenceEmissionProperties properties;
    private final ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> diagnosticPipeline;
    private final EngineIntelligenceEmissionMetrics metrics;

    public EngineIntelligenceEmissionService(
            EngineIntelligenceEmissionProperties properties,
            ObjectProvider<EngineIntelligenceDiagnosticEnrichmentPipeline> diagnosticPipeline,
            EngineIntelligenceEmissionMetrics metrics
    ) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
        this.diagnosticPipeline = Objects.requireNonNull(diagnosticPipeline, "diagnosticPipeline is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    public Optional<EngineIntelligenceSummary> emitIfEnabled(FraudScoringRequest scoringRequest) {
        Objects.requireNonNull(scoringRequest, "scoringRequest is required");
        if (!properties.emitEnabled()) {
            recordMetrics(metrics::recordSkippedDisabled);
            return Optional.empty();
        }
        recordMetrics(metrics::recordAttempt);
        long startedAtNanos = System.nanoTime();
        try {
            EngineIntelligenceDiagnosticEnrichmentPipeline pipeline = diagnosticPipeline.getIfAvailable();
            if (pipeline == null) {
                recordMetrics(() -> metrics.recordOmitted(EngineIntelligenceEmissionOmissionReason.PIPELINE_UNAVAILABLE));
                log.warn("Engine intelligence enrichment omitted.");
                return Optional.empty();
            }
            Optional<EngineIntelligenceSummary> result = pipeline.enrich(scoringRequest);
            if (result.isPresent()) {
                recordMetrics(metrics::recordSuccess);
            } else {
                recordMetrics(() -> metrics.recordOmitted(EngineIntelligenceEmissionOmissionReason.EMPTY_RESULT));
            }
            return result;
        } catch (RuntimeException exception) {
            recordMetrics(() -> metrics.recordOmitted(EngineIntelligenceEmissionOmissionReason.UNKNOWN_FAILURE));
            log.warn("Engine intelligence enrichment omitted.");
            return Optional.empty();
        } finally {
            recordMetrics(() -> metrics.recordLatency(Duration.ofNanos(elapsedNanos(startedAtNanos))));
        }
    }

    public boolean emitEnabled() {
        return properties.emitEnabled();
    }

    private long elapsedNanos(long startedAtNanos) {
        return Math.max(0L, System.nanoTime() - startedAtNanos);
    }

    private void recordMetrics(Runnable metricsCall) {
        try {
            metricsCall.run();
        } catch (RuntimeException exception) {
            log.warn("Engine intelligence metrics recording omitted.");
        }
    }
}
