package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryNoAuthorizationHeaderLeakTest {

    @Test
    void authorizationHeaderValuesDoNotReachMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new SecurityDeniedAccessTelemetryRecorder(registry).record(new SecurityDeniedAccessSnapshot(
                "suspicious_transaction_read",
                "unauthorized",
                "GET",
                "anonymous"
        ));

        assertThat(meterIds(registry))
                .doesNotContain("secret-token-value", "Authorization", "Bearer", "JWT", "token");
    }

    private String meterIds(SimpleMeterRegistry registry) {
        return registry.getMeters().stream().map(Meter::getId).map(Object::toString).collect(Collectors.joining("\n"));
    }
}
