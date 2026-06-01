package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionScoredEventCompatibilityTest {

    @Test
    void oldTransactionScoredEventJsonWithoutEngineIntelligenceDeserializes() throws Exception {
        assertThat(objectMapper().readValue(oldJson(), TransactionScoredEvent.class).engineIntelligence()).isNull();
    }

    @Test
    void newTransactionScoredEventJsonWithEngineIntelligenceDeserializes() throws Exception {
        TransactionScoredEvent event = objectMapper().readValue(newJson(""), TransactionScoredEvent.class);

        assertThat(event.engineIntelligence()).isNotNull();
        assertThat(event.engineIntelligence().contractVersion()).isEqualTo(1);
    }

    @Test
    void missingEngineIntelligenceIsAccepted() throws Exception {
        assertThat(objectMapper().readValue(oldJson(), TransactionScoredEvent.class).riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void nullEngineIntelligenceIsAccepted() throws Exception {
        assertThat(objectMapper().readValue(oldJson().replace(
                "\"alertRecommended\": true",
                "\"alertRecommended\": true, \"engineIntelligence\": null"
        ), TransactionScoredEvent.class).engineIntelligence()).isNull();
    }

    @Test
    void unknownFutureEngineIntelligenceFieldsAreIgnored() throws Exception {
        assertThat(objectMapper().readValue(newJson(", \"futureBoundedField\": \"ignored\""), TransactionScoredEvent.class)
                .engineIntelligence()).isNotNull();
    }

    @Test
    void existingTransactionScoredEventFieldsRemainUnchanged() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents()).map(RecordComponent::getName))
                .containsSubsequence(
                        "eventId", "transactionId", "correlationId", "customerId", "accountId", "createdAt",
                        "transactionTimestamp", "transactionAmount", "merchantInfo", "deviceInfo", "locationInfo",
                        "customerContext", "fraudScore", "riskLevel", "scoringStrategy", "modelName", "modelVersion",
                        "inferenceTimestamp", "reasonCodes", "scoreDetails", "featureSnapshot", "alertRecommended",
                        "scoringEvidence", "engineIntelligence"
                );
    }

    @Test
    void existingScoredEventSerializationWithoutEngineIntelligenceRemainsStable() throws Exception {
        assertThat(objectMapper().writeValueAsString(oldConstructorEvent())).doesNotContain("engineIntelligence");
    }

    @Test
    void newEngineIntelligenceFieldIsOptional() {
        assertThat(oldConstructorEvent().engineIntelligence()).isNull();
    }

    private TransactionScoredEvent oldConstructorEvent() {
        return new TransactionScoredEvent(
                "evt-1", "txn-1", "corr-1", "cust-1", "acct-1",
                Instant.parse("2026-06-01T06:00:00Z"), Instant.parse("2026-06-01T06:00:00Z"),
                null, null, null, null, null, 0.82d, RiskLevel.HIGH, "RULE_BASED", "rules", "v1",
                Instant.parse("2026-06-01T06:00:01Z"), List.of("HIGH_VELOCITY"), Map.of(), Map.of(), true
        );
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private String oldJson() {
        return """
                {
                  "eventId": "evt-1",
                  "transactionId": "txn-1",
                  "correlationId": "corr-1",
                  "customerId": "cust-1",
                  "accountId": "acct-1",
                  "createdAt": "2026-06-01T06:00:00Z",
                  "transactionTimestamp": "2026-06-01T06:00:00Z",
                  "fraudScore": 0.82,
                  "riskLevel": "HIGH",
                  "scoringStrategy": "RULE_BASED",
                  "modelName": "rules",
                  "modelVersion": "v1",
                  "inferenceTimestamp": "2026-06-01T06:00:01Z",
                  "reasonCodes": ["HIGH_VELOCITY"],
                  "scoreDetails": {},
                  "featureSnapshot": {},
                  "alertRecommended": true
                }
                """;
    }

    private String newJson(String futureField) {
        return oldJson().replace(
                "\"alertRecommended\": true",
                """
                "alertRecommended": true,
                "engineIntelligence": {
                  "contractVersion": 1,
                  "generatedAt": "2026-06-01T06:00:00Z",
                  "engines": [],
                  "comparison": {
                    "agreementStatus": "INSUFFICIENT_DATA",
                    "riskMismatchStatus": "NOT_COMPARABLE",
                    "scoreDeltaBucket": "UNAVAILABLE"
                  },
                  "diagnosticSignals": [],
                  "warnings": []
                  %s
                }
                """.formatted(futureField)
        );
    }
}
