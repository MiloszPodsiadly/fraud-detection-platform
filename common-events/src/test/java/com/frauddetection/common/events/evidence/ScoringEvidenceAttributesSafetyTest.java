package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringEvidenceAttributesSafetyTest {

    @Test
    void allowsSafeBoundedPrimitiveMetadata() {
        Map<String, Object> attributes = ScoringEvidenceAttributes.safeCopy(Map.of(
                "diagnostic", true,
                "unsupportedReasonCodeCount", 2,
                "parseStatus", "UNSUPPORTED",
                "fallbackReasonCode", "ml_request_failed"
        ));

        assertThat(attributes)
                .containsEntry("diagnostic", true)
                .containsEntry("unsupportedReasonCodeCount", 2)
                .containsEntry("parseStatus", "UNSUPPORTED")
                .containsEntry("fallbackReasonCode", "ml_request_failed");
    }

    @Test
    void nullAttributesBecomeEmptyMapAndReturnedMapIsImmutable() {
        Map<String, Object> attributes = ScoringEvidenceAttributes.safeCopy(null);

        assertThat(attributes).isEmpty();
        assertThatThrownBy(() -> attributes.put("diagnostic", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNestedMapAndArbitraryObjects() {
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("diagnostic", Map.of("value", true))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("diagnostic", new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void futureHarmlessAttributesAreAllowed() {
        Map<String, Object> attributes = ScoringEvidenceAttributes.safeCopy(Map.of(
                "futureHarmlessAttribute", "safe-bounded-value",
                "ruleBucket", "high-risk-rule",
                "modelSignalBucket", "0.75-1.00",
                "diagnosticVersion", 1
        ));

        assertThat(attributes)
                .containsEntry("futureHarmlessAttribute", "safe-bounded-value")
                .containsEntry("ruleBucket", "high-risk-rule")
                .containsEntry("modelSignalBucket", "0.75-1.00")
                .containsEntry("diagnosticVersion", 1);
    }

    @Test
    void rejectsUnsafeAttributes() {
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("rawUnsupportedReasonCode", "future-code")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("rawPayload", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("featureSnapshot", "dump")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("customerId", "cust-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("accountId", "acct-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("cardNumber", "4111111111111111")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("iban", "PL61109010140000071219812874")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("pesel", "44051401359")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("ssn", "123-45-6789")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("email", "analyst@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("phone", "+48123456789")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("address", "Main Street")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("fullName", "Test Analyst")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("modelPayload", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("fallbackReason", "raw request failed at http://internal")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fallbackReasonCodeIsAllowedButRawFallbackReasonIsRejected() {
        assertThat(ScoringEvidenceAttributes.safeCopy(Map.of("fallbackReasonCode", "ml_request_failed")))
                .containsEntry("fallbackReasonCode", "ml_request_failed");

        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("fallbackReason", "ML request failed with raw exception")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullBlankAndUnsafeValues() {
        Map<String, Object> nullKey = new java.util.HashMap<>();
        nullKey.put(null, "value");
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(nullKey))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of(" ", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(java.util.Collections.singletonMap("futureHarmlessAttribute", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsListsOfBoundedPrimitiveValues() {
        Map<String, Object> attributes = ScoringEvidenceAttributes.safeCopy(Map.of(
                "futureHarmlessAttribute", java.util.List.of("safe", 1, true)
        ));

        assertThat(attributes).containsEntry("futureHarmlessAttribute", java.util.List.of("safe", 1, true));
    }

    @Test
    void rejectsLongStringsConsistently() {
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("parseStatus", "x".repeat(257))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");

        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("futureHarmlessAttribute", java.util.List.of("x".repeat(257)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }
}
