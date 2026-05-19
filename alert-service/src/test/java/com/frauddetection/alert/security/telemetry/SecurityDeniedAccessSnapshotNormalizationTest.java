package com.frauddetection.alert.security.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessSnapshotNormalizationTest {

    @Test
    void arbitraryRawValuesCollapseToBoundedFallbacks() {
        SecurityDeniedAccessSnapshot snapshot = maliciousSnapshot();

        assertThat(snapshot.routeGroup()).isEqualTo("unknown");
        assertThat(snapshot.outcome()).isEqualTo("unauthorized");
        assertThat(snapshot.method()).isEqualTo("OTHER");
        assertThat(snapshot.authState()).isEqualTo("unknown");
    }

    @Test
    void maliciousValuesDoNotSurviveSnapshotOrMetricTags() {
        SecurityDeniedAccessSnapshot snapshot = maliciousSnapshot();

        assertThat(snapshot.toString()).doesNotContain(
                "suspicious-secret-123",
                "cursor-secret",
                "customer-123",
                "m.podsiadly99@gmail.com",
                "Bearer token",
                "AccessDeniedException raw message"
        );
        assertThat(snapshot.metricTags().stream().map(tag -> tag.getValue()).toList())
                .doesNotContain(
                        "suspicious-secret-123",
                        "cursor-secret",
                        "customer-123",
                        "m.podsiadly99@gmail.com",
                        "Bearer token",
                        "AccessDeniedException raw message"
                );
    }

    private SecurityDeniedAccessSnapshot maliciousSnapshot() {
        return new SecurityDeniedAccessSnapshot(
                "/internal/suspicious-transactions/suspicious-secret-123?cursor=cursor-secret",
                "AccessDeniedException raw message",
                "Bearer token",
                "m.podsiadly99@gmail.com customer-123"
        );
    }
}
