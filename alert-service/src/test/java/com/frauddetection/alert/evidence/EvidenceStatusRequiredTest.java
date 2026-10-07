package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

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
                null,
                null,
                null,
                null,
                EvidenceType.DIAGNOSTIC,
                EvidenceSource.FRAUD_SCORING_SERVICE,
                null,
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
                .hasMessageContaining("status is required");
    }
}
