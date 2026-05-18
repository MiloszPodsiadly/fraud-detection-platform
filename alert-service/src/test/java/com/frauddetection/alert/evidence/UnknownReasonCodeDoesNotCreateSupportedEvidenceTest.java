package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class UnknownReasonCodeDoesNotCreateSupportedEvidenceTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void unknownDoesNotCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("UNKNOWN")
        ));

        assertThat(evidence).hasSize(1);
        assertThat(evidence).noneSatisfy(item -> {
            assertThat(item.getReasonCode()).isEqualTo("UNKNOWN");
            assertThat(item.getStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        });
        assertThat(evidence.getFirst().getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
    }

    @Test
    void unsupportedVerdictLikeReasonCodesDoNotCreateSupportedEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.CRITICAL,
                List.of("FRAUD_CONFIRMED", "AML_ESCALATION_REQUIRED")
        ));

        assertThat(evidence).hasSize(2);
        assertThat(evidence).allSatisfy(item -> {
            assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
            assertThat(item.getStatus()).isEqualTo(EvidenceStatus.ERROR);
            assertThat(item.getReasonCode()).isNull();
            assertThat(item.getAttributes()).containsEntry("supportedEvidenceCreated", false);
        });
    }
}
