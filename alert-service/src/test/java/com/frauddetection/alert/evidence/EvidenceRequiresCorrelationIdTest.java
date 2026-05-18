package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRequiresCorrelationIdTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void projectedEvidenceCarriesCorrelationIdWhenEventHasOne() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence.getFirst().getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void missingCorrelationIdDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                "event-1",
                "txn-1",
                null,
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence).hasSize(1);
        EvidenceDocument diagnostic = evidence.getFirst();
        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getValue()).isEqualTo("missing_correlation_id");
        assertThat(diagnostic.getAttributes())
                .containsEntry("missingCorrelationId", true)
                .containsEntry("correlationIdState", "null")
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeApplicable", false)
                .containsEntry("evidenceProjectionState", "missing_correlation_id");
        assertThat(evidence).noneMatch(item -> item.getStatus() == EvidenceStatus.AVAILABLE);
    }

    @Test
    void blankCorrelationIdDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                "event-1",
                "txn-1",
                "   ",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence).hasSize(1);
        EvidenceDocument diagnostic = evidence.getFirst();
        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getAttributes())
                .containsEntry("missingCorrelationId", true)
                .containsEntry("correlationIdState", "blank")
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeApplicable", false)
                .containsEntry("evidenceProjectionState", "missing_correlation_id");
        assertThat(evidence).noneMatch(item -> item.getStatus() == EvidenceStatus.AVAILABLE);
    }
}
