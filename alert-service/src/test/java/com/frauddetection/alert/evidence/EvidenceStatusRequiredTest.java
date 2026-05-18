package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceStatusRequiredTest {

    @Test
    void evidenceFactoryRejectsMissingStatus() {
        assertThatThrownBy(() -> EvidenceDocument.create(EvidenceSource.FRAUD_SCORING_SERVICE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
    }

    @Test
    void snapshotItemRejectsMissingStatus() {
        assertThatThrownBy(() -> new EvidenceSnapshotItem(
                null,
                EvidenceType.DIAGNOSTIC,
                EvidenceSeverity.LOW,
                EvidenceSource.FRAUD_SCORING_SERVICE,
                null,
                "Diagnostic",
                "Diagnostic context",
                null,
                null,
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status is required");
    }
}
