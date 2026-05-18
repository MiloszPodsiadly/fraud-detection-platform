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

    @Test
    void missingSourceEventIdDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                null,
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertSourceEventLinkageDiagnostic(evidence, "null");
    }

    @Test
    void blankSourceEventIdDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                "   ",
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertSourceEventLinkageDiagnostic(evidence, "blank");
    }

    @Test
    void availableEvidenceAlwaysHasSourceEventId() {
        EvidenceDocument evidence = service.projectFromScoredEvent(scoredEvent(
                "event-lineage-4",
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(evidence.getStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(evidence.getSourceEventId()).isEqualTo("event-lineage-4");
        assertThat(evidence.getEvidenceId()).contains("event-lineage-4");
        assertThat(evidence.getEvidenceId()).doesNotContain("missing-event");
    }

    @Test
    void evidenceIdDoesNotUseMissingEventForAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                null,
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence).noneMatch(item -> item.getStatus() == EvidenceStatus.AVAILABLE);
        assertThat(evidence.getFirst().getEvidenceId()).contains("missing-event");
    }

    private void assertSourceEventLinkageDiagnostic(List<EvidenceDocument> evidence, String expectedState) {
        assertThat(evidence).hasSize(1);
        EvidenceDocument diagnostic = evidence.getFirst();
        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getValue()).isEqualTo("missing_source_event_id");
        assertThat(diagnostic.getAttributes())
                .containsEntry("missingSourceEventId", true)
                .containsEntry("sourceEventIdState", expectedState)
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeApplicable", false)
                .containsEntry("evidenceProjectionState", EvidenceProjectionState.PARTIAL_MISSING_SOURCE_EVENT_ID.name());
        assertThat(evidence).noneMatch(item -> item.getStatus() == EvidenceStatus.AVAILABLE);
    }
}
