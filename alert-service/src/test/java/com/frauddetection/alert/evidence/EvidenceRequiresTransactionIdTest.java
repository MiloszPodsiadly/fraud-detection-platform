package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRequiresTransactionIdTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void missingTransactionIdDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                "event-1",
                null,
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertTransactionLinkageDiagnostic(evidence, "null");
    }

    @Test
    void blankTransactionIdDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                "event-1",
                "   ",
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertTransactionLinkageDiagnostic(evidence, "blank");
    }

    @Test
    void generatedDiagnosticIdDoesNotUseUnknownTransactionForAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                "event-1",
                null,
                "corr-1",
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence).noneMatch(item -> item.getStatus() == EvidenceStatus.AVAILABLE);
        assertThat(evidence.getFirst().getEvidenceId()).doesNotContain("unknown-transaction");
    }

    private void assertTransactionLinkageDiagnostic(List<EvidenceDocument> evidence, String expectedState) {
        assertThat(evidence).hasSize(1);
        EvidenceDocument diagnostic = evidence.getFirst();
        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(diagnostic.getStatus()).isNotEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getValue()).isEqualTo("missing_transaction_id");
        assertThat(diagnostic.getAttributes())
                .containsEntry("missingTransactionId", true)
                .containsEntry("transactionIdState", expectedState)
                .containsEntry("supportedEvidenceCreated", false)
                .containsEntry("evidenceProjectionState", "missing_transaction_id");
        assertThat(evidence).noneMatch(item -> item.getStatus() == EvidenceStatus.AVAILABLE);
    }
}
