package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceSnapshotItemContractTest {

    private static final Instant PROJECTED_AT = Instant.parse("2026-05-18T10:00:10Z");

    @Test
    void availableSnapshotRequiresReasonCode() {
        assertThatThrownBy(() -> snapshot(null, EvidenceType.GEO_SIGNAL, EvidenceStatus.AVAILABLE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availableSnapshotRejectsUnknownReasonCode() {
        assertThatThrownBy(() -> snapshot("UNKNOWN", EvidenceType.GEO_SIGNAL, EvidenceStatus.AVAILABLE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availableSnapshotRejectsTrimmedLowercaseUnknownReasonCode() {
        assertThatThrownBy(() -> snapshot(" unknown ", EvidenceType.GEO_SIGNAL, EvidenceStatus.AVAILABLE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void availableSnapshotCannotBeDiagnostic() {
        assertThatThrownBy(() -> snapshot("COUNTRY_MISMATCH", EvidenceType.DIAGNOSTIC, EvidenceStatus.AVAILABLE, diagnosticAttributes()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosticSnapshotRequiresDiagnosticMetadata() {
        assertThatThrownBy(() -> snapshot(null, EvidenceType.DIAGNOSTIC, EvidenceStatus.PARTIAL, Map.of("diagnostic", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attributesAreDefensivelyCopied() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("futureHarmlessAttribute", "safe");

        EvidenceSnapshotItem item = snapshot("COUNTRY_MISMATCH", EvidenceType.GEO_SIGNAL, EvidenceStatus.AVAILABLE, attributes);
        attributes.put("futureHarmlessAttribute", "mutated");

        assertThat(item.attributes()).containsEntry("futureHarmlessAttribute", "safe");
        assertThatThrownBy(() -> item.attributes().put("anotherAttribute", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noForbiddenVerdictFields() {
        assertThat(Arrays.stream(EvidenceSnapshotItem.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("fraudConfirmed", "verdict", "finalOutcome", "proof", "legalProof");
    }

    private EvidenceSnapshotItem snapshot(
            String reasonCode,
            EvidenceType evidenceType,
            EvidenceStatus status,
            Map<String, Object> attributes
    ) {
        return new EvidenceSnapshotItem(
                "event-1:evidence-1:0",
                "event-1",
                "txn-1",
                "corr-1",
                reasonCode,
                evidenceType,
                EvidenceSource.FRAUD_SCORING_SERVICE,
                status,
                EvidenceSeverity.HIGH,
                "Title",
                "Description",
                null,
                null,
                attributes,
                Instant.parse("2026-05-18T10:00:00Z"),
                PROJECTED_AT,
                "RULE_BASED",
                "rule-based",
                "v1",
                Instant.parse("2026-05-18T10:00:00Z")
        );
    }

    private Map<String, Object> diagnosticAttributes() {
        return Map.of(
                "diagnostic", true,
                "supportedEvidenceCreated", false,
                "reasonCodeApplicable", false
        );
    }
}
