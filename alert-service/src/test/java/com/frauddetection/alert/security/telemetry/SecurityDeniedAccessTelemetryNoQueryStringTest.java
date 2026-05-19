package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryNoQueryStringTest {

    @Test
    void queryStringValuesDoNotReachMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        String routeGroup = new SecurityDeniedAccessRouteClassifier().classify(
                "/internal/suspicious-transactions/suspicious-secret-123?cursor=cursor-secret&customerId=customer-secret"
        );

        new SecurityDeniedAccessTelemetryRecorder(registry).record(new SecurityDeniedAccessSnapshot(
                routeGroup,
                "unauthorized",
                "GET",
                "anonymous"
        ));

        assertThat(meterIds(registry))
                .contains("tag(routeGroup=suspicious_transaction_read)")
                .doesNotContain(
                        "cursor-secret",
                        "customer-secret",
                        "cursor=",
                        "customerId=",
                        "?cursor=",
                        "/internal/suspicious-transactions/suspicious-secret-123?"
                );
    }

    private String meterIds(SimpleMeterRegistry registry) {
        return registry.getMeters().stream().map(Meter::getId).map(Object::toString).collect(Collectors.joining("\n"));
    }
}
