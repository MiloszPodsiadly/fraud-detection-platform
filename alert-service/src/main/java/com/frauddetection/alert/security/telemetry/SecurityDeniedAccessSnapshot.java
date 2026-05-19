package com.frauddetection.alert.security.telemetry;

import io.micrometer.core.instrument.Tags;

import java.util.Set;

public record SecurityDeniedAccessSnapshot(
        String routeGroup,
        String outcome,
        String method,
        String authState
) {

    static final Set<String> ROUTE_GROUPS = Set.of(
            "suspicious_transaction_read",
            "fraud_alert",
            "fraud_case",
            "trust",
            "internal_other",
            "unknown"
    );
    static final Set<String> OUTCOMES = Set.of("unauthorized", "forbidden");
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OTHER");
    static final Set<String> AUTH_STATES = Set.of("anonymous", "authenticated", "unknown");

    public SecurityDeniedAccessSnapshot {
        routeGroup = allow(routeGroup, ROUTE_GROUPS, "unknown");
        outcome = allow(outcome, OUTCOMES, "unauthorized");
        method = allow(method, METHODS, "OTHER");
        authState = allow(authState, AUTH_STATES, "unknown");
    }

    Tags metricTags() {
        return Tags.of(
                "routeGroup", routeGroup,
                "outcome", outcome,
                "method", method,
                "authState", authState
        );
    }

    private static String allow(String value, Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return allowed.contains(value) ? value : fallback;
    }
}
