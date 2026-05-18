package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
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
                "safeSignals", List.of("PARTIAL", 7, false)
        ));

        assertThat(attributes)
                .containsEntry("diagnostic", true)
                .containsEntry("unsupportedReasonCodeCount", 2)
                .containsEntry("parseStatus", "UNSUPPORTED");
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
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("nested", Map.of("value", true))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("object", new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSensitiveOrUnboundedAttributeKeys() {
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("rawUnsupportedReasonCode", "future-code")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("featureSnapshot", "dump")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("customerId", "cust-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("accountId", "acct-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("cardNumber", "4111111111111111")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLongStringsConsistently() {
        assertThatThrownBy(() -> ScoringEvidenceAttributes.safeCopy(Map.of("parseStatus", "x".repeat(257))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }
}
