package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerEngineIntelligenceEmissionMetricsTest {

    @Test
    void evidenceOmissionUsesOnlyBoundedReasonLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new MicrometerEngineIntelligenceEmissionMetrics(registry);

        metrics.recordEvidenceOmitted(MlPredictionEvidenceOmissionReason.SOURCE_TIMESTAMP_MISSING);

        assertThat(registry.get(MicrometerEngineIntelligenceEmissionMetrics.EVIDENCE_OMISSION_COUNTER)
                .tag("reason", "SOURCE_TIMESTAMP_MISSING")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void currentEmissionSignalsAreRecorded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new MicrometerEngineIntelligenceEmissionMetrics(registry);

        metrics.recordAttempt();
        metrics.recordSuccess();
        metrics.recordOmitted(EngineIntelligenceEmissionOmissionReason.EMPTY_RESULT);
        metrics.recordLatency(Duration.ofMillis(5));

        assertThat(registry.get(MicrometerEngineIntelligenceEmissionMetrics.EMISSION_COUNTER)
                .tag("outcome", "ATTEMPTED").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerEngineIntelligenceEmissionMetrics.EMISSION_COUNTER)
                .tag("outcome", "SUCCEEDED").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerEngineIntelligenceEmissionMetrics.EMISSION_OMISSION_COUNTER)
                .tag("reason", "EMPTY_RESULT").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerEngineIntelligenceEmissionMetrics.EMISSION_LATENCY_TIMER)
                .timer().count()).isEqualTo(1L);
    }
}
