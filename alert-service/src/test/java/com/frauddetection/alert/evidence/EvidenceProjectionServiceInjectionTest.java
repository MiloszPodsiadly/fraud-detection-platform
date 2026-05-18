package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceProjectionServiceInjectionTest {

    @Test
    void publicConstructorUsesInjectedReasonCodeMapper() {
        EvidenceProjectionService service = new EvidenceProjectionService(new ReasonCodeEvidenceTypeMapper());

        EvidenceDocument evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        )).getFirst();

        assertThat(evidence.getEvidenceType()).isEqualTo(EvidenceType.GEO_SIGNAL);
    }
}
