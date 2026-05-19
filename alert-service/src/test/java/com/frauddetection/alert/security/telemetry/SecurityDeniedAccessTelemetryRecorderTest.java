package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityDeniedAccessTelemetryRecorderTest {

    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("routeGroup", "outcome", "method", "authState");

    @Test
    void recorderRequiresMeterRegistry() {
        assertThatThrownBy(() -> new SecurityDeniedAccessTelemetryRecorder(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("meterRegistry is required");
    }

    @Test
    void recordsDeniedAccessMetricWithStrictTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityDeniedAccessTelemetryRecorder recorder = new SecurityDeniedAccessTelemetryRecorder(registry);

        recorder.record(new SecurityDeniedAccessSnapshot(
                "suspicious_transaction_read",
                "unauthorized",
                "GET",
                "anonymous"
        ));

        assertThat(registry.get(SecurityDeniedAccessTelemetryRecorder.DENIED_ACCESS_METRIC)
                .tag("routeGroup", "suspicious_transaction_read")
                .tag("outcome", "unauthorized")
                .tag("method", "GET")
                .tag("authState", "anonymous")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(tagKeys(registry)).containsExactlyInAnyOrderElementsOf(ALLOWED_TAG_KEYS);
    }

    @Test
    void arbitraryMaliciousSnapshotValuesAreNormalizedBeforeRecording() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityDeniedAccessTelemetryRecorder recorder = new SecurityDeniedAccessTelemetryRecorder(registry);

        recorder.record(new SecurityDeniedAccessSnapshot(
                "/internal/suspicious-transactions/suspicious-secret-123?cursor=cursor-secret",
                "exceptionMessage=customer-123",
                "TRACE",
                "m.podsiadly99@gmail.com"
        ));

        String meterText = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        assertThat(meterText)
                .contains(
                        "tag(routeGroup=unknown)",
                        "tag(outcome=unauthorized)",
                        "tag(method=OTHER)",
                        "tag(authState=unknown)"
                )
                .doesNotContain(
                        "suspicious-secret-123",
                        "cursor-secret",
                        "customer-123",
                        "m.podsiadly99@gmail.com",
                        "exceptionMessage"
                );
    }

    private Set<String> tagKeys(SimpleMeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(SecurityDeniedAccessTelemetryRecorder.DENIED_ACCESS_METRIC))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
    }
}
