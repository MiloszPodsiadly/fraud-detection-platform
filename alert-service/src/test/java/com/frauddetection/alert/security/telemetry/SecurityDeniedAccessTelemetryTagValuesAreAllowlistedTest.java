package com.frauddetection.alert.security.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessTelemetryTagValuesAreAllowlistedTest {

    @Test
    void tagKeysAndValuesAreAllowlisted() {
        SecurityDeniedAccessSnapshot snapshot = new SecurityDeniedAccessSnapshot(
                "suspicious_transaction_read",
                "forbidden",
                "GET",
                "authenticated"
        );

        assertThat(SecurityDeniedAccessSnapshot.ROUTE_GROUPS).contains(snapshot.routeGroup());
        assertThat(SecurityDeniedAccessSnapshot.OUTCOMES).contains(snapshot.outcome());
        assertThat(SecurityDeniedAccessSnapshot.METHODS).contains(snapshot.method());
        assertThat(SecurityDeniedAccessSnapshot.AUTH_STATES).contains(snapshot.authState());
        assertThat(snapshot.metricTags().stream().map(tag -> tag.getKey()).toList())
                .containsExactlyInAnyOrder("routeGroup", "outcome", "method", "authState");
    }
}
