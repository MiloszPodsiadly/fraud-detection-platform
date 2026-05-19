package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryNoRawPathVariablesTest {

    @Test
    void suspiciousTransactionPathVariableDoesNotReachMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityDeniedAccessRouteClassifier classifier = new SecurityDeniedAccessRouteClassifier();

        new SecurityDeniedAccessTelemetryRecorder(registry).record(new SecurityDeniedAccessSnapshot(
                classifier.classify("/internal/suspicious-transactions/suspicious-secret-123"),
                "unauthorized",
                "GET",
                "anonymous"
        ));

        assertThat(meterIds(registry))
                .contains("tag(routeGroup=suspicious_transaction_read)")
                .doesNotContain("suspicious-secret-123", "/internal/suspicious-transactions/suspicious-secret-123");
    }

    private String meterIds(SimpleMeterRegistry registry) {
        return registry.getMeters().stream().map(Meter::getId).map(Object::toString).collect(Collectors.joining("\n"));
    }
}
