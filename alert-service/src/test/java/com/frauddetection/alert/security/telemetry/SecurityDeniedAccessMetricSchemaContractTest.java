package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessMetricSchemaContractTest {

    private static final Set<String> NEW_TAG_KEYS = Set.of("routeGroup", "outcome", "method", "authState");
    private static final Set<String> LEGACY_TAG_KEYS = Set.of("auth_type", "endpoint", "reason", "actor_type");

    @Test
    void securityDeniedAccessMetricUsesOnlyNewTagSchema() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityDeniedAccessTelemetryRecorder recorder = new SecurityDeniedAccessTelemetryRecorder(registry);

        recorder.record(new SecurityDeniedAccessSnapshot(
                "suspicious_transaction_read",
                "forbidden",
                "GET",
                "authenticated"
        ));

        assertThat(tagKeys(registry)).containsExactlyInAnyOrderElementsOf(NEW_TAG_KEYS);
    }

    @Test
    void securityDeniedAccessMetricDoesNotEmitLegacyTagKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityDeniedAccessTelemetryRecorder recorder = new SecurityDeniedAccessTelemetryRecorder(registry);

        recorder.record(new SecurityDeniedAccessSnapshot("fraud_alert", "unauthorized", "POST", "anonymous"));

        assertThat(tagKeys(registry)).doesNotContainAnyElementsOf(LEGACY_TAG_KEYS);
    }

    @Test
    void docsMentionMetricContractChange() throws Exception {
        String docs = Files.readString(Path.of("../docs/security/security_denied_access_telemetry.md"));
        String normalized = docs.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("metric contract change")
                .contains("replaces the previous access-denied metric tag schema")
                .contains("the metric name remains `fraud.security.access.denied`")
                .contains("auth_type")
                .contains("endpoint")
                .contains("reason")
                .contains("actor_type")
                .contains("must migrate to `routegroup`, `outcome`, `method`, and `authstate`")
                .contains("does not dual-emit legacy tags");
    }

    private Set<String> tagKeys(SimpleMeterRegistry registry) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(SecurityDeniedAccessTelemetryRecorder.DENIED_ACCESS_METRIC))
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());
    }
}
