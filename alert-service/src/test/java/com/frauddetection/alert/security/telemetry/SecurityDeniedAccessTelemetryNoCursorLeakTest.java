package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryNoCursorLeakTest {

    @Test
    void cursorTokenAndDecodedPayloadDoNotReachMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        String cursor = "eyJkZXRlY3RlZEF0IjoiMjAyNi0wMS0wMVQwMDowMDowMFoiLCJzdXNwaWNpb3VzVHJhbnNhY3Rpb25JZCI6InNlY3JldCJ9";
        String routeGroup = new SecurityDeniedAccessRouteClassifier().classify(
                "/internal/suspicious-transactions/suspicious-secret-123?cursor=" + cursor
        );

        new SecurityDeniedAccessTelemetryRecorder(registry).record(new SecurityDeniedAccessSnapshot(
                routeGroup,
                "unauthorized",
                "GET",
                "anonymous"
        ));

        assertThat(meterIds(registry))
                .contains("tag(routeGroup=suspicious_transaction_read)")
                .doesNotContain(cursor, "2026-01-01T00:00:00Z", "secret", "detectedAt", "cursor");
    }

    private String meterIds(SimpleMeterRegistry registry) {
        return registry.getMeters().stream().map(Meter::getId).map(Object::toString).collect(Collectors.joining("\n"));
    }
}
