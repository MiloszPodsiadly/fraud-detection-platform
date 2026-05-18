package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

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
                EvidenceType.DIAGNOSTIC,
                EvidenceSeverity.LOW,
                null,
                EvidenceStatus.PARTIAL,
                "Diagnostic",
                "Diagnostic context",
                null,
                null,
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source is required");
    }
}
