package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
    void missingCorrelationIdRemainsExplicitOnProjectedEvidence() {
        TransactionScoredEvent event = new TransactionScoredEvent(
                "event-1",
                "txn-1",
                null,
                "customer-1",
                "account-1",
                EvidenceProjectionTestSupport.CREATED_AT,
                EvidenceProjectionTestSupport.CREATED_AT,
                null,
                null,
                null,
                null,
                null,
                0.91d,
                RiskLevel.HIGH,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                EvidenceProjectionTestSupport.INFERENCE_AT,
                List.of("COUNTRY_MISMATCH"),
                Map.of(),
                Map.of(),
                true
        );

        List<EvidenceDocument> evidence = service.projectFromScoredEvent(event);

        assertThat(evidence.getFirst().getCorrelationId()).isNull();
        assertThat(evidence.getFirst().getTransactionId()).isEqualTo("txn-1");
    }
}
