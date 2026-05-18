package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionScoredEventScoringEvidenceAdditiveContractTest {

    @Test
    void scoringEvidenceSerializesWithoutReplacingExistingFields() throws Exception {
        TransactionScoredEvent event = new TransactionScoredEvent(
                "evt-1",
                "txn-1",
                "corr-1",
                "cust-1",
                "acct-1",
                Instant.parse("2026-05-18T09:00:00Z"),
                Instant.parse("2026-05-18T09:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                0.82d,
                RiskLevel.HIGH,
                "ML",
                "python-logistic-fraud-model",
                "test-version",
                Instant.parse("2026-05-18T09:00:01Z"),
                List.of("MODEL_HIGH_RISK"),
                Map.of("modelAvailable", true),
                Map.of("featureFlagCount", 1),
                true,
                List.of(evidence())
        );

        String json = objectMapper().writeValueAsString(event);

        assertThat(json)
                .contains("reasonCodes")
                .contains("scoreDetails")
                .contains("featureSnapshot")
                .contains("alertRecommended")
                .contains("scoringEvidence")
                .contains("MODEL_HIGH_RISK");
    }

    @Test
    void emptyScoringEvidenceDoesNotImplyFraudDecisionFailure() {
        TransactionScoredEvent event = new TransactionScoredEvent(
                "evt-1",
                "txn-1",
                "corr-1",
                "cust-1",
                "acct-1",
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                0.12d,
                RiskLevel.LOW,
                "ML",
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                List.of(),
                Map.of("modelAvailable", true),
                Map.of(),
                false,
                null
        );

        assertThat(event.scoringEvidence()).isEmpty();
        assertThat(event.alertRecommended()).isFalse();
        assertThat(event.reasonCodes()).isEmpty();
    }

    private ScoringEvidenceItem evidence() {
        return new ScoringEvidenceItem(
                "ML_MODEL:MODEL_HIGH_RISK:0",
                "MODEL_HIGH_RISK",
                ScoringEvidenceType.MODEL_EXPLANATION,
                ScoringEvidenceSource.ML_MODEL,
                ScoringEvidenceStatus.AVAILABLE,
                ScoringEvidenceSeverity.HIGH,
                "High model risk",
                "Model output indicated a high scoring signal.",
                null,
                null,
                Map.of("parseStatus", "KNOWN"),
                Instant.parse("2026-05-18T09:00:01Z")
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
