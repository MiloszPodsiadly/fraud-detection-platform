package com.frauddetection.alert.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertServiceMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AlertServiceMetrics metrics = new AlertServiceMetrics(meterRegistry);

    @Test
    void shouldUseOnlyBoundedLifecycleMetricLabels() {
        metrics.recordGovernanceAdvisoryLifecycle(
                "ACKNOWLEDGED",
                "python-logistic-fraud-model",
                "2026-04-21.trained.v1"
        );

        Meter meter = meterRegistry.get("fraud_ml_governance_advisory_lifecycle_total").meter();

        assertThat(meter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("lifecycle_status", "model_name", "model_version");
        assertThat(meter.getId().getTags())
                .extracting(Tag::getKey)
                .doesNotContain("event_id", "actor_id", "audit_id", "endpoint", "reason", "exception");
    }

    @Test
    void shouldUseNoDynamicLabelsForAnalyticsMetrics() {
        metrics.recordGovernanceAnalyticsRequest(7);

        Meter requestMeter = meterRegistry.get("fraud_ml_governance_analytics_requests_total").meter();
        Meter windowMeter = meterRegistry.get("fraud_ml_governance_analytics_window_days").meter();

        assertThat(requestMeter.getId().getTags()).isEmpty();
        assertThat(windowMeter.getId().getTags()).isEmpty();
        assertThat(meterRegistry.get("fraud_ml_governance_analytics_window_days").gauge().value()).isEqualTo(7.0);
    }
}
