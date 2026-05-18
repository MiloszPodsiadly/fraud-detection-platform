package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceProjectionRequiredFieldsTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void projectedAvailableEvidenceHasRequiredFields() {
        EvidenceDocument evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(evidence.getStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(evidence.getEvidenceId()).isNotBlank();
        assertThat(evidence.getTransactionId()).isNotBlank();
        assertThat(evidence.getCorrelationId()).isNotBlank();
        assertThat(evidence.getSourceEventId()).isNotBlank();
        assertThat(evidence.getSource()).isNotNull();
        assertThat(evidence.getEvidenceType()).isNotNull();
        assertThat(evidence.getSeverity()).isNotNull();
        assertThat(evidence.getReasonCode()).isNotBlank();
        assertThat(evidence.getCreatedAt()).isNotNull();
        assertThat(evidence.getObservedAt()).isNotNull();
        assertThat(evidence.getEvidenceId()).doesNotContain("missing-event", "missing-correlation", "missing-transaction");
    }

    @Test
    void projectedDiagnosticEvidenceHasRequiredFields() {
        EvidenceDocument diagnostic = service.projectFromScoredEvent(scoredEvent(
                null,
                "txn-1",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(diagnostic.getEvidenceId()).isNotBlank();
        assertThat(diagnostic.getSource()).isNotNull();
        assertThat(diagnostic.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getSeverity()).isNotNull();
        assertThat(diagnostic.getValue()).isNotBlank();
        assertThat(diagnostic.getCreatedAt()).isNotNull();
        assertThat(diagnostic.getObservedAt()).isNotNull();
        assertThat(diagnostic.getAttributes())
                .containsEntry("diagnostic", true)
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("reasonCodeApplicable", false);
    }
}
