package com.frauddetection.alert.persistence;

import com.frauddetection.alert.evidence.EvidenceSeverity;
import com.frauddetection.alert.evidence.EvidenceSnapshotItem;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import com.frauddetection.alert.evidence.EvidenceType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertDocumentEvidenceSnapshotTest {

    @Test
    void nullSnapshotNormalizesToEmptyList() {
        AlertDocument document = new AlertDocument();

        document.setEvidenceSnapshot(null);

        assertThat(document.getEvidenceSnapshot()).isEmpty();
    }

    @Test
    void setterDefensivelyCopiesSnapshotAndGetterIsImmutable() {
        AlertDocument document = new AlertDocument();
        List<EvidenceSnapshotItem> source = new ArrayList<>();
        source.add(item());

        document.setEvidenceSnapshot(source);
        source.clear();

        assertThat(document.getEvidenceSnapshot()).hasSize(1);
        assertThatThrownBy(() -> document.getEvidenceSnapshot().add(item()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void setterRejectsUnboundedEvidenceSnapshot() {
        AlertDocument document = new AlertDocument();
        List<EvidenceSnapshotItem> oversized = Collections.nCopies(AlertDocument.MAX_EVIDENCE_SNAPSHOT_ITEMS + 1, item());

        assertThatThrownBy(() -> document.setEvidenceSnapshot(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceSnapshot must not exceed");
    }

    @Test
    void setterAcceptsHardMaxEvidenceSnapshot() {
        AlertDocument document = new AlertDocument();
        List<EvidenceSnapshotItem> hardMax = Collections.nCopies(AlertDocument.MAX_EVIDENCE_SNAPSHOT_ITEMS, item());

        document.setEvidenceSnapshot(hardMax);

        assertThat(document.getEvidenceSnapshot()).hasSize(AlertDocument.MAX_EVIDENCE_SNAPSHOT_ITEMS);
    }

    @Test
    void evidenceSnapshotFieldDoesNotImplyFraudFields() {
        assertThat(AlertDocument.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("fraudConfirmed", "verdict", "finalOutcome", "proof", "legalProof");
    }

    private EvidenceSnapshotItem item() {
        return new EvidenceSnapshotItem(
                "event-1:evidence-1:0",
                "event-1",
                "txn-1",
                "corr-1",
                "COUNTRY_MISMATCH",
                EvidenceType.GEO_SIGNAL,
                EvidenceSource.FRAUD_SCORING_SERVICE,
                EvidenceStatus.AVAILABLE,
                EvidenceSeverity.HIGH,
                "Country mismatch",
                "Description",
                null,
                null,
                Map.of(),
                Instant.parse("2026-05-18T10:00:00Z"),
                Instant.parse("2026-05-18T10:01:00Z"),
                "RULE_BASED",
                "rule-based",
                "v1",
                Instant.parse("2026-05-18T10:00:00Z")
        );
    }
}
