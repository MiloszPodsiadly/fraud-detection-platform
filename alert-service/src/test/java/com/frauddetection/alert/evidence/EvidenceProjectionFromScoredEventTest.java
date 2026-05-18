package com.frauddetection.alert.evidence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.frauddetection.alert.evidence.EvidenceProjectionTestSupport.scoredEvent;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceProjectionFromScoredEventTest {

    private static final Instant CREATED_AT = Instant.parse("2026-05-18T11:00:00Z");

    private final EvidenceProjectionService service = new EvidenceProjectionService(
            new ReasonCodeEvidenceTypeMapper(),
            Clock.fixed(CREATED_AT, ZoneOffset.UTC)
    );

    @Test
    void supportedReasonCodesCreateAvailableEvidenceWithScoredEventFields() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.HIGH,
                List.of("COUNTRY_MISMATCH")
        ));

        assertThat(evidence).hasSize(1);
        EvidenceDocument item = evidence.getFirst();
        assertThat(item.getSource()).isEqualTo(EvidenceSource.FRAUD_SCORING_SERVICE);
        assertThat(item.getStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.GEO_SIGNAL);
        assertThat(item.getSeverity()).isEqualTo(EvidenceSeverity.HIGH);
        assertThat(item.getReasonCode()).isEqualTo("COUNTRY_MISMATCH");
        assertThat(item.getTransactionId()).isEqualTo("txn-1");
        assertThat(item.getCustomerId()).isEqualTo("customer-1");
        assertThat(item.getCorrelationId()).isEqualTo("corr-1");
        assertThat(item.getSourceEventId()).isEqualTo("event-1");
        assertThat(item.getEvidenceId()).contains("event-1");
        assertThat(item.getObservedAt()).isEqualTo(EvidenceProjectionTestSupport.INFERENCE_AT);
        assertThat(item.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(item.getScoringStrategy()).isEqualTo("RULE_BASED");
        assertThat(item.getModelName()).isEqualTo("rule-based-engine");
        assertThat(item.getModelVersion()).isEqualTo("v1");
        assertThat(item.getEntityType()).isEqualTo(EvidenceEntityType.SCORED_TRANSACTION);
        assertThat(item.getEntityId()).isEqualTo("txn-1");
    }

    @Test
    void rapidTransferFraudCaseIsProjectedAsCandidateVelocitySignalOnly() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.CRITICAL,
                List.of("RAPID_TRANSFER_FRAUD_CASE")
        ));

        EvidenceDocument item = evidence.getFirst();
        String text = (item.getTitle() + " " + item.getDescription()).toLowerCase(java.util.Locale.ROOT);
        assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.VELOCITY_SIGNAL);
        assertThat(item.getStatus()).isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(text).containsAnyOf("candidate", "signal");
        assertThat(text).doesNotContain("confirmed");
        assertThat(text).doesNotContain("fraud case exists");
        assertThat(text).doesNotContain("verdict");
        assertThat(text).doesNotContain("final");
    }

    @Test
    void modelUnavailableCreatesModelExplanationWithoutCustomerFraudClaim() {
        List<EvidenceDocument> evidence = service.projectFromScoredEvent(scoredEvent(
                RiskLevel.LOW,
                List.of("ML_MODEL_UNAVAILABLE")
        ));

        EvidenceDocument item = evidence.getFirst();
        assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.MODEL_EXPLANATION);
        assertThat(item.getDescription()).contains("runtime was unavailable");
        assertThat(item.getDescription()).doesNotContain("confirmed fraud");
    }
}
