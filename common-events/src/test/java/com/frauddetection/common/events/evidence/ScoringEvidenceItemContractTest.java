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
    void normalizesNullAttributesToEmptyMap() {
        ScoringEvidenceItem item = item(ScoringEvidenceSource.RULE_BASED_SCORING, ScoringEvidenceStatus.AVAILABLE, null);

        assertThat(item.attributes()).isEmpty();
    }

    @Test
    void defensivelyCopiesAndFreezesAttributes() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("diagnostic", true);

        ScoringEvidenceItem item = item(ScoringEvidenceSource.ML_MODEL, ScoringEvidenceStatus.PARTIAL, source);
        source.put("diagnostic", false);

        assertThat(item.attributes()).containsEntry("diagnostic", true);
        assertThatThrownBy(() -> item.attributes().put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reasonCodeMayBeNullForDiagnosticContexts() {
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
                Map.of("diagnostic", true),
                Instant.now()
        );

        assertThat(item.reasonCode()).isNull();
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
                "COUNTRY_MISMATCH",
                ScoringEvidenceType.GEO_SIGNAL,
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
}
