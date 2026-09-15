package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class CanonicalScoredTransactionEvidenceTest {

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(Instant.parse("2026-05-18T11:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void canonicalReasonCodesCreateAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence).hasSize(1);
        EvidenceDocument item = evidence.getFirst();
        assertThat(item.getReasonCode()).isEqualTo("COUNTRY_MISMATCH");
        assertThat(item.getStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.GEO_SIGNAL);
        assertThat(item.getAttributes()).containsEntry("reasonCodeParseStatus", "KNOWN");
    }

    @Test
    void featureNameReasonCodeDataDoesNotBecomeAvailableEvidence() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("countryMismatch")
        ));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().getEvidenceType()).isEqualTo(EvidenceType.DIAGNOSTIC);
        assertThat(evidence.getFirst().getStatus()).isEqualTo(EvidenceStatus.ERROR);
        assertThat(evidence.getFirst().getReasonCode()).isNull();
    }
}
