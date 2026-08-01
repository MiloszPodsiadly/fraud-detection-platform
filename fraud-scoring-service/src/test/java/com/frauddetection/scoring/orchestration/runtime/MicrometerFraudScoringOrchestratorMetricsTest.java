package com.frauddetection.scoring.orchestration.runtime;

import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.engine.FraudEngineType;
import com.frauddetection.scoring.orchestration.FraudScoringOrchestrationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerFraudScoringOrchestratorMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerFraudScoringOrchestratorMetrics metrics =
            new MicrometerFraudScoringOrchestratorMetrics(registry);

    @Test
    void recordsBoundedResultFailureTimeoutRequiredAndLatencyMeters() {
        metrics.recordOrchestration(FraudScoringOrchestrationStatus.PARTIAL);
        metrics.recordEngineResult("rules.primary", FraudEngineType.RULES, FraudEngineStatus.AVAILABLE, true);
        metrics.recordEngineResult("velocity.primary", FraudEngineType.VELOCITY, FraudEngineStatus.DEGRADED, false);
        metrics.recordEngineFailure("velocity.primary", FraudEngineType.VELOCITY, "publication_failure");
        metrics.recordTimeout("ml.python.primary", FraudEngineType.ML_MODEL, false);
        metrics.recordRequiredEngineFailed("rules.primary");
        metrics.recordEngineLatency(
                "velocity.primary",
                FraudEngineType.VELOCITY,
                FraudEngineStatus.DEGRADED,
                false,
                Duration.ofMillis(17)
        );

        assertThat(registry.get(MicrometerFraudScoringOrchestratorMetrics.ORCHESTRATION_RESULT_COUNTER)
                .tag("status", "PARTIAL").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerFraudScoringOrchestratorMetrics.ENGINE_RESULT_COUNTER)
                .tag("engine_id", "velocity.primary")
                .tag("engine_type", "VELOCITY")
                .tag("status", "DEGRADED")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerFraudScoringOrchestratorMetrics.ENGINE_FAILURE_COUNTER)
                .tag("engine_id", "velocity.primary")
                .tag("engine_type", "VELOCITY")
                .tag("failure_category", "publication_failure")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerFraudScoringOrchestratorMetrics.ENGINE_TIMEOUT_COUNTER)
                .tag("engine_id", "ml.python.primary")
                .tag("engine_type", "ML_MODEL")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerFraudScoringOrchestratorMetrics.REQUIRED_ENGINE_FAILED_COUNTER)
                .tag("engine_id", "rules.primary").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(MicrometerFraudScoringOrchestratorMetrics.ENGINE_LATENCY_TIMER)
                .tag("engine_id", "velocity.primary")
                .tag("engine_type", "VELOCITY")
                .tag("status", "DEGRADED")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    void rejectsUnboundedMetricLabels() {
        assertThatThrownBy(() -> metrics.recordEngineFailure(
                "velocity.primary",
                FraudEngineType.VELOCITY,
                "raw-token-stacktrace-account-123"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("METRICS_UNKNOWN_FAILURE_CATEGORY");
    }

    @Test
    void metersUseOnlyAllowlistedTagKeys() {
        recordsBoundedResultFailureTimeoutRequiredAndLatencyMeters();

        Set<String> tagKeys = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());

        assertThat(tagKeys).containsOnly("status", "engine_id", "engine_type", "failure_category");
    }
}
