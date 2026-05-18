package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceLineageIdentityTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void evidenceIdIncludesSourceEventIdentity() {
        EvidenceDocument evidence = service.projectFromScoredEvent(scoredEvent(
                "event-lineage-1",
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(evidence.getSourceEventId()).isEqualTo("event-lineage-1");
        assertThat(evidence.getEvidenceId()).contains("event-lineage-1");
    }

    @Test
    void sameTransactionDifferentScoredEventsDoNotCollideEvidenceIds() {
        EvidenceDocument first = service.projectFromScoredEvent(scoredEvent(
                "event-lineage-1",
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();
        EvidenceDocument second = service.projectFromScoredEvent(scoredEvent(
                "event-lineage-2",
                "txn-1",
                "corr-2",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(first.getEvidenceId()).isNotEqualTo(second.getEvidenceId());
    }

    @Test
    void sourceEventIdIsPersistedOnProjectedEvidence() {
        EvidenceDocument evidence = service.projectFromScoredEvent(scoredEvent(
                "event-lineage-3",
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(evidence.getSourceEventId()).isEqualTo("event-lineage-3");
    }
}
