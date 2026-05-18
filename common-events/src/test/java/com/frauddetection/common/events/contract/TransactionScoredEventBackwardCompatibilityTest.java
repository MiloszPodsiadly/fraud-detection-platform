package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.enums.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionScoredEventBackwardCompatibilityTest {

    @Test
    void oldConstructorKeepsScoringEvidenceEmpty() {
        TransactionScoredEvent event = oldEvent();

        assertThat(event.reasonCodes()).containsExactly("COUNTRY_MISMATCH");
        assertThat(event.scoreDetails()).containsEntry("finalScore", 0.82d);
        assertThat(event.featureSnapshot()).containsEntry("featureFlagCount", 1);
        assertThat(event.scoringEvidence()).isEmpty();
    }

    @Test
    void missingScoringEvidenceDeserializesWithoutBreakingOldFields() throws Exception {
        String json = """
                {
                  "eventId": "evt-1",
                  "transactionId": "txn-1",
                  "correlationId": "corr-1",
                  "customerId": "cust-1",
                  "accountId": "acct-1",
                  "createdAt": "2026-05-18T09:00:00Z",
                  "transactionTimestamp": "2026-05-18T09:00:00Z",
                  "fraudScore": 0.82,
                  "riskLevel": "HIGH",
                  "scoringStrategy": "RULE_BASED",
                  "modelName": "rule-based-engine",
                  "modelVersion": "v1",
                  "inferenceTimestamp": "2026-05-18T09:00:01Z",
                  "reasonCodes": ["COUNTRY_MISMATCH"],
                  "scoreDetails": {"finalScore": 0.82},
                  "featureSnapshot": {"featureFlagCount": 1},
                  "alertRecommended": true
                }
                """;

        TransactionScoredEvent event = objectMapper().readValue(json, TransactionScoredEvent.class);

        assertThat(event.reasonCodes()).containsExactly("COUNTRY_MISMATCH");
        assertThat(event.scoreDetails()).containsEntry("finalScore", 0.82d);
        assertThat(event.featureSnapshot()).containsEntry("featureFlagCount", 1);
        assertThat(event.scoringEvidence()).isEmpty();
    }

    @Test
    void harmlessFutureScoringEvidenceAttributeDoesNotBreakDeserialization() throws Exception {
        String json = """
                {
                  "eventId": "evt-1",
                  "transactionId": "txn-1",
                  "correlationId": "corr-1",
                  "customerId": "cust-1",
                  "accountId": "acct-1",
                  "createdAt": "2026-05-18T09:00:00Z",
                  "transactionTimestamp": "2026-05-18T09:00:00Z",
                  "fraudScore": 0.82,
                  "riskLevel": "HIGH",
                  "scoringStrategy": "RULE_BASED",
                  "modelName": "rule-based-engine",
                  "modelVersion": "v1",
                  "inferenceTimestamp": "2026-05-18T09:00:01Z",
                  "reasonCodes": ["COUNTRY_MISMATCH"],
                  "scoreDetails": {"finalScore": 0.82},
                  "featureSnapshot": {"featureFlagCount": 1},
                  "alertRecommended": true,
                  "scoringEvidence": [{
                    "evidenceId": "RULE_BASED_SCORING:country_mismatch:0",
                    "reasonCode": "COUNTRY_MISMATCH",
                    "evidenceType": "GEO_SIGNAL",
                    "source": "RULE_BASED_SCORING",
                    "status": "AVAILABLE",
                    "severity": "HIGH",
                    "title": "Country mismatch",
                    "description": "Transaction geography differed from expected context.",
                    "attributes": {"futureHarmlessAttribute": "safe-bounded-value"},
                    "observedAt": "2026-05-18T09:00:01Z"
                  }]
                }
                """;

        TransactionScoredEvent event = objectMapper().readValue(json, TransactionScoredEvent.class);

        assertThat(event.scoringEvidence()).singleElement().satisfies(item ->
                assertThat(item.attributes()).containsEntry("futureHarmlessAttribute", "safe-bounded-value"));
    }

    private TransactionScoredEvent oldEvent() {
        return new TransactionScoredEvent(
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
                0.82d,
                RiskLevel.HIGH,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.now(),
                List.of("COUNTRY_MISMATCH"),
                Map.of("finalScore", 0.82d),
                Map.of("featureFlagCount", 1),
                true
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
