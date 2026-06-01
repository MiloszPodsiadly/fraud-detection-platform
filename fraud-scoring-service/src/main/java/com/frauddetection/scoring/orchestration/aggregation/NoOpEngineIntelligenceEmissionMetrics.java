package com.frauddetection.scoring.orchestration.aggregation;

import java.time.Duration;
import java.util.Objects;

public final class NoOpEngineIntelligenceEmissionMetrics implements EngineIntelligenceEmissionMetrics {

    @Override
    public void recordSkippedDisabled() {
    }

    @Override
    public void recordAttempt() {
    }

    @Override
    public void recordSuccess() {
    }

    @Override
    public void recordOmitted(EngineIntelligenceEmissionOmissionReason reason) {
        Objects.requireNonNull(reason, "reason is required");
    }

    @Override
    public void recordLatency(Duration latency) {
        Objects.requireNonNull(latency, "latency is required");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_EMISSION_METRICS_NEGATIVE_LATENCY");
        }
    }
}
