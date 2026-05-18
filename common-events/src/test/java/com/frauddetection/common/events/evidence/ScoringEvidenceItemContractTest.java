package com.frauddetection.common.events.evidence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoringEvidenceItemContractTest {

    @Test
    void requiresSourceAndStatus() {
        assertThatThrownBy(() -> item(null, ScoringEvidenceStatus.AVAILABLE, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source is required");

        assertThatThrownBy(() -> item(ScoringEvidenceSource.RULE_BASED_SCORING, null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
    }

    @Test
    void availableScoringEvidenceRequiresReasonCode() {
        assertThatThrownBy(() -> available(null, ScoringEvidenceType.GEO_SIGNAL, ScoringEvidenceSeverity.HIGH, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode is required");

        assertThatThrownBy(() -> available(" ", ScoringEvidenceType.GEO_SIGNAL, ScoringEvidenceSeverity.HIGH, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode is required");
    }

    @Test
    void availableScoringEvidenceRejectsUnknownReasonCode() {
        assertThatThrownBy(() -> available("UNKNOWN", ScoringEvidenceType.MODEL_EXPLANATION, ScoringEvidenceSeverity.LOW, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN cannot be AVAILABLE");
    }

    @Test
    void availableRejectsLowercaseUnknownReasonCode() {
        assertThatThrownBy(() -> available("unknown", ScoringEvidenceType.MODEL_EXPLANATION, ScoringEvidenceSeverity.LOW, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN cannot be AVAILABLE");
    }

    @Test
    void availableRejectsTrimmedUnknownReasonCode() {
        assertThatThrownBy(() -> available(" UNKNOWN ", ScoringEvidenceType.MODEL_EXPLANATION, ScoringEvidenceSeverity.LOW, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN cannot be AVAILABLE");
    }

    @Test
    void availableScoringEvidenceCannotBeDiagnostic() {
        assertThatThrownBy(() -> available("COUNTRY_MISMATCH", ScoringEvidenceType.DIAGNOSTIC, ScoringEvidenceSeverity.HIGH, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AVAILABLE scoring evidence cannot be DIAGNOSTIC");
    }

    @Test
    void availableScoringEvidenceRequiresEvidenceTypeSeverityAndObservedAt() {
        assertThatThrownBy(() -> available("COUNTRY_MISMATCH", null, ScoringEvidenceSeverity.HIGH, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("evidenceType is required");

        assertThatThrownBy(() -> available("COUNTRY_MISMATCH", ScoringEvidenceType.GEO_SIGNAL, null, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("severity is required");

        assertThatThrownBy(() -> available("COUNTRY_MISMATCH", ScoringEvidenceType.GEO_SIGNAL, ScoringEvidenceSeverity.HIGH, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("observedAt is required");
    }

    @Test
    void normalizesNullAttributesToEmptyMap() {
        ScoringEvidenceItem item = item(ScoringEvidenceSource.RULE_BASED_SCORING, ScoringEvidenceStatus.AVAILABLE, null);

        assertThat(item.attributes()).isEmpty();
    }

    @Test
    void defensivelyCopiesAndFreezesAttributes() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("diagnostic", true);
        source.put("supportedEvidenceCreated", false);
        source.put("reasonCodeApplicable", false);

        ScoringEvidenceItem item = item(ScoringEvidenceSource.ML_MODEL, ScoringEvidenceStatus.PARTIAL, source);
        source.put("diagnostic", false);

        assertThat(item.attributes()).containsEntry("diagnostic", true);
        assertThatThrownBy(() -> item.attributes().put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void diagnosticPartialMayHaveNullReasonCode() {
        ScoringEvidenceItem item = new ScoringEvidenceItem(
                "ML_RUNTIME:diagnostic:0",
                null,
                ScoringEvidenceType.DIAGNOSTIC,
                ScoringEvidenceSource.ML_RUNTIME,
                ScoringEvidenceStatus.PARTIAL,
                ScoringEvidenceSeverity.LOW,
                "Diagnostic context",
                "Diagnostic evidence context.",
                null,
                null,
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false
                ),
                Instant.now()
        );

        assertThat(item.reasonCode()).isNull();
    }

    @Test
    void diagnosticEvidenceRequiresDiagnosticMetadata() {
        assertThatThrownBy(() -> new ScoringEvidenceItem(
                "ML_RUNTIME:diagnostic:0",
                null,
                ScoringEvidenceType.DIAGNOSTIC,
                ScoringEvidenceSource.ML_RUNTIME,
                ScoringEvidenceStatus.PARTIAL,
                ScoringEvidenceSeverity.LOW,
                "Diagnostic context",
                "Diagnostic evidence context.",
                null,
                null,
                Map.of("diagnostic", true),
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DIAGNOSTIC scoring evidence requires diagnostic metadata");
    }

    @Test
    void dtoRejectsUnsafeCustomerIdAttribute() {
        assertThatThrownBy(() -> availableWithAttributes(Map.of("customerId", "cust-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dtoRejectsRawPayloadAttribute() {
        assertThatThrownBy(() -> availableWithAttributes(Map.of("rawModelPayload", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dtoRejectsNestedAttributeMap() {
        assertThatThrownBy(() -> availableWithAttributes(Map.of("futureHarmlessAttribute", Map.of("nested", true))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dtoRejectsArbitraryAttributeObject() {
        assertThatThrownBy(() -> availableWithAttributes(Map.of("futureHarmlessAttribute", new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dtoAllowsFutureHarmlessAttribute() {
        ScoringEvidenceItem item = availableWithAttributes(Map.of("futureHarmlessAttribute", "safe-bounded-value"));

        assertThat(item.attributes()).containsEntry("futureHarmlessAttribute", "safe-bounded-value");
    }

    @Test
    void attributesMapIsImmutable() {
        ScoringEvidenceItem item = availableWithAttributes(Map.of("futureHarmlessAttribute", "safe-bounded-value"));

        assertThatThrownBy(() -> item.attributes().put("anotherSafeAttribute", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotExposeForbiddenVerdictOrProofFields() {
        assertThat(Arrays.stream(ScoringEvidenceItem.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain(
                        "fraudConfirmed",
                        "isFraud",
                        "verdict",
                        "finalOutcome",
                        "proof",
                        "legalProof",
                        "worm",
                        "notarized",
                        "auditAuthority",
                        "externalVerification"
                );
    }

    private ScoringEvidenceItem item(
            ScoringEvidenceSource source,
            ScoringEvidenceStatus status,
            Map<String, Object> attributes
    ) {
        return new ScoringEvidenceItem(
                "evidence-id",
                status == ScoringEvidenceStatus.AVAILABLE ? "COUNTRY_MISMATCH" : null,
                status == ScoringEvidenceStatus.AVAILABLE ? ScoringEvidenceType.GEO_SIGNAL : ScoringEvidenceType.DIAGNOSTIC,
                source,
                status,
                ScoringEvidenceSeverity.HIGH,
                "Country mismatch",
                "Transaction geography differed from expected context.",
                null,
                null,
                attributes,
                Instant.now()
        );
    }

    private ScoringEvidenceItem available(
            String reasonCode,
            ScoringEvidenceType evidenceType,
            ScoringEvidenceSeverity severity,
            Instant observedAt
    ) {
        return new ScoringEvidenceItem(
                "evidence-id",
                reasonCode,
                evidenceType,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                ScoringEvidenceStatus.AVAILABLE,
                severity,
                "Country mismatch",
                "Transaction geography differed from expected context.",
                null,
                null,
                Map.of(),
                observedAt
        );
    }

    private ScoringEvidenceItem availableWithAttributes(Map<String, Object> attributes) {
        return new ScoringEvidenceItem(
                "evidence-id",
                "COUNTRY_MISMATCH",
                ScoringEvidenceType.GEO_SIGNAL,
                ScoringEvidenceSource.RULE_BASED_SCORING,
                ScoringEvidenceStatus.AVAILABLE,
                ScoringEvidenceSeverity.HIGH,
                "Country mismatch",
                "Transaction geography differed from expected context.",
                null,
                null,
                attributes,
                Instant.now()
        );
    }
}
