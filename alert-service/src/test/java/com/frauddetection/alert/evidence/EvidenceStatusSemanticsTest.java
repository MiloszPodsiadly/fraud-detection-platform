package com.frauddetection.alert.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceStatusSemanticsTest {

    @Test
    void statusValuesStaySemanticallyDistinct() {
        assertThat(EvidenceStatus.PARTIAL).isNotEqualTo(EvidenceStatus.UNAVAILABLE);
        assertThat(EvidenceStatus.UNAVAILABLE).isNotEqualTo(EvidenceStatus.ERROR);
        assertThat(EvidenceStatus.LEGACY).isNotEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(EvidenceStatus.NOT_APPLICABLE).isNotEqualTo(EvidenceStatus.UNAVAILABLE);
    }

    @Test
    void evidenceDocumentHasNoDefaultAvailableStatus() {
        EvidenceDocument document = new EvidenceDocument();

        assertThat(document.getStatus()).isNull();
    }
}
