package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceSourceRequiredTest {

    @Test
    void evidenceFactoryRejectsMissingSource() {
        assertThatThrownBy(() -> EvidenceDocument.create(null, EvidenceStatus.PARTIAL))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source is required");
    }

    @Test
    void snapshotItemRejectsMissingSource() {
        assertThatThrownBy(() -> new EvidenceSnapshotItem(
                null,
                null,
                null,
                null,
                null,
                EvidenceType.DIAGNOSTIC,
                null,
                EvidenceStatus.PARTIAL,
                EvidenceSeverity.LOW,
                "Diagnostic",
                "Diagnostic context",
                null,
                null,
                Map.of(
                        "diagnostic", true,
                        "supportedEvidenceCreated", false,
                        "reasonCodeApplicable", false
                ),
                null,
                Instant.parse("2026-05-18T10:00:00Z"),
                null,
                null,
                null,
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source is required");
    }
}
