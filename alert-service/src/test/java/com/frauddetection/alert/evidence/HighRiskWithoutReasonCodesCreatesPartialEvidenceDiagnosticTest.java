package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class HighRiskWithoutReasonCodesCreatesPartialEvidenceDiagnosticTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void highRiskWithEmptyReasonCodesCreatesPartialDiagnosticEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(RiskLevel.HIGH, List.of()));

        assertThat(evidence).hasSize(1);
        EvidenceDocument diagnostic = evidence.getFirst();
        assertThat(diagnostic.getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(diagnostic.getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(diagnostic.getReasonCode()).isNull();
        assertThat(diagnostic.getDescription()).contains("lacks supported reason codes");
        assertThat(diagnostic.getStatus()).isNotEqualTo(EvidenceStatus.AVAILABLE);
    }

    @Test
    void criticalRiskWithNullReasonCodesCreatesPartialDiagnosticEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(RiskLevel.CRITICAL, null));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().getStatus()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(evidence.getFirst().getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
    }

    @Test
    void lowRiskWithNullReasonCodesDoesNotCreateFakeAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(RiskLevel.LOW, null));

        assertThat(evidence).isEmpty();
    }
}
