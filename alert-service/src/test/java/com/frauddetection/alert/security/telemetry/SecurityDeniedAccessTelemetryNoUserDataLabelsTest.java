package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryNoUserDataLabelsTest {

    @Test
    void userAndBusinessDataCannotAppearInMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new SecurityDeniedAccessTelemetryRecorder(registry).record(new SecurityDeniedAccessSnapshot(
                "customer-123",
                "account-456",
                "cursor-secret",
                "m.podsiadly99@gmail.com"
        ));

        String tags = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        assertThat(tags).doesNotContain(
                "customer-123",
                "account-456",
                "cursor-secret",
                "m.podsiadly99@gmail.com",
                "username",
                "email"
        );
    }
}
