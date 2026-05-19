package com.frauddetection.alert.security.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityDeniedAccessRouteClassifierTest {

    private final SecurityDeniedAccessRouteClassifier classifier = new SecurityDeniedAccessRouteClassifier();

    @Test
    void classifiesSuspiciousTransactionRoutesWithoutRawPathValues() {
        assertThat(classifier.classify("/internal/suspicious-transactions"))
                .isEqualTo("suspicious_transaction_read");
        assertThat(classifier.classify("/internal/suspicious-transactions/suspicious-secret-123"))
                .isEqualTo("suspicious_transaction_read");
    }

    @Test
    void ignoresQueryStringAndCursorPayload() {
        String routeGroup = classifier.classify(
                "/internal/suspicious-transactions/suspicious-secret-123?cursor=cursor-secret&customerId=customer-123"
        );

        assertThat(routeGroup)
                .isEqualTo("suspicious_transaction_read")
                .doesNotContain("suspicious-secret-123", "cursor-secret", "customer-123", "cursor=");
    }

    @Test
    void classifiesInternalFallbackAndUnknownRoutes() {
        assertThat(classifier.classify("/internal/anything")).isEqualTo("internal_other");
        assertThat(classifier.classify("/public/anything")).isEqualTo("unknown");
        assertThat(classifier.classify(null)).isEqualTo("unknown");
        assertThat(classifier.classify("")).isEqualTo("unknown");
    }

    @Test
    void unknownRouteNeverFallsBackToRawPath() {
        String routeGroup = classifier.classify("/api/v1/not-classified/raw-secret-id");

        assertThat(routeGroup)
                .isEqualTo("unknown")
                .doesNotContain("raw-secret-id", "/api/v1/not-classified");
    }

    @Test
    void newUnclassifiedInternalRouteFallsBackToInternalOther() {
        String routeGroup = classifier.classify("/internal/new-route-family/raw-secret-id");

        assertThat(routeGroup)
                .isEqualTo("internal_other")
                .doesNotContain("raw-secret-id", "/internal/new-route-family");
    }

    @Test
    void classifiesKnownStableRouteGroups() {
        assertThat(classifier.classify("/api/v1/alerts/alert-1")).isEqualTo("fraud_alert");
        assertThat(classifier.classify("/api/v1/fraud-cases/case-1")).isEqualTo("fraud_case");
        assertThat(classifier.classify("/system/trust-level")).isEqualTo("trust");
        assertThat(classifier.classify("/api/v1/audit/trust/attestation")).isEqualTo("trust");
    }
}
