package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

public final class MicrometerEngineIntelligenceEmissionMetrics implements EngineIntelligenceEmissionMetrics {

    static final String EMISSION_COUNTER = "engine_intelligence_emission_total";
    static final String EMISSION_OMISSION_COUNTER = "engine_intelligence_emission_omitted_total";
    static final String EVIDENCE_OMISSION_COUNTER = "ml_prediction_evidence_omitted_total";
    static final String EMISSION_LATENCY_TIMER = "engine_intelligence_emission_latency_seconds";

    private final MeterRegistry meterRegistry;

    public MicrometerEngineIntelligenceEmissionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    @Override
    public void recordSkippedDisabled() {
        emission("SKIPPED_DISABLED");
    }

    @Override
    public void recordAttempt() {
        emission("ATTEMPTED");
    }

    @Override
    public void recordSuccess() {
        emission("SUCCEEDED");
    }

    @Override
    public void recordOmitted(EngineIntelligenceEmissionOmissionReason reason) {
        Counter.builder(EMISSION_OMISSION_COUNTER)
                .tag("reason", Objects.requireNonNull(reason, "reason is required").name())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordEvidenceOmitted(MlPredictionEvidenceOmissionReason reason) {
        Counter.builder(EVIDENCE_OMISSION_COUNTER)
                .tag("reason", Objects.requireNonNull(reason, "reason is required").name())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordLatency(Duration latency) {
        Duration boundedLatency = Objects.requireNonNull(latency, "latency is required");
        if (boundedLatency.isNegative()) {
            throw new IllegalArgumentException("ENGINE_INTELLIGENCE_EMISSION_METRICS_NEGATIVE_LATENCY");
        }
        Timer.builder(EMISSION_LATENCY_TIMER).register(meterRegistry).record(boundedLatency);
    }

    private void emission(String outcome) {
        Counter.builder(EMISSION_COUNTER)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
