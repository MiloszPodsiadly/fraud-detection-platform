package com.frauddetection.common.events.contract;

import tools.jackson.databind.ObjectMapper;

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
    void currentTransactionScoredEventJsonWithoutEngineIntelligenceDeserializes() throws Exception {
        assertThat(objectMapper().readValue(currentJson(), TransactionScoredEvent.class).engineIntelligence()).isNull();
        assertThat(objectMapper().readValue(currentJson(), TransactionScoredEvent.class).analystRecommendation()).isNull();
    }

    @Test
    void newTransactionScoredEventJsonWithEngineIntelligenceDeserializes() throws Exception {
        TransactionScoredEvent event = objectMapper().readValue(newJson(""), TransactionScoredEvent.class);

        assertThat(event.engineIntelligence()).isNotNull();
        assertThat(event.engineIntelligence().contractVersion()).isEqualTo(1);
    }

    @Test
    void engineIntelligenceMlModelIdentityRoundTripsAsAdditiveOptionalField() throws Exception {
        TransactionScoredEvent event = objectMapper().readValue(newJsonWithMlIdentity(), TransactionScoredEvent.class);

        assertThat(event.modelVersion()).isEqualTo("final-score-v2");
        assertThat(event.engineIntelligence().engines().get(1).modelIdentity().modelVersion())
                .isEqualTo("ml-shadow-2026-06-01");
        assertThat(objectMapper().writeValueAsString(event))
                .contains("\"modelIdentity\"")
                .contains("\"featureContractVersion\":\"2026-05-30.feature-contract.v1\"");
    }

    @Test
    void missingEngineIntelligenceIsAccepted() throws Exception {
        assertThat(objectMapper().readValue(currentJson(), TransactionScoredEvent.class).riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void nullEngineIntelligenceIsAccepted() throws Exception {
        assertThat(objectMapper().readValue(currentJson().replace(
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
    void unknownTopLevelFutureFieldIsIgnored() throws Exception {
        TransactionScoredEvent event = objectMapper().readValue(
                newJson("").replace(
                        "\"eventId\": \"evt-1\",",
                        "\"eventId\": \"evt-1\", \"futureTopLevelField\": \"ignored\","
                ),
                TransactionScoredEvent.class
        );

        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(event.engineIntelligence()).isNotNull();
    }

    @Test
    void newTransactionScoredEventJsonWithAnalystRecommendationDeserializes() throws Exception {
        TransactionScoredEvent event = objectMapper().readValue(newJsonWithAnalystRecommendation(), TransactionScoredEvent.class);

        assertThat(event.analystRecommendation()).isNotNull();
        assertThat(event.analystRecommendation().status().name()).isEqualTo("AVAILABLE");
        assertThat(event.analystRecommendation().recommendation().name()).isEqualTo("RECOMMEND_REVIEW");
        assertThat(event.analystRecommendation().recommendationVersion()).isEqualTo("analyst-recommendation-v1");
        assertThat(event.analystRecommendation().generatedAt()).isEqualTo(Instant.parse("2026-06-19T10:00:00Z"));
        assertThat(event.analystRecommendation().nonDecisioning().notPaymentAuthorization()).isTrue();
        assertThat(event.analystRecommendation().nonDecisioning().notAutomaticDecisioning()).isTrue();
        assertThat(event.analystRecommendation().nonDecisioning().notCaseAction()).isTrue();
    }

    @Test
    void existingTransactionScoredEventFieldsRemainUnchanged() {
        assertThat(Arrays.stream(TransactionScoredEvent.class.getRecordComponents()).map(RecordComponent::getName))
                .containsSubsequence(
                        "eventId", "transactionId", "correlationId", "customerId", "accountId", "createdAt",
                        "transactionTimestamp", "transactionAmount", "merchantInfo", "deviceInfo", "locationInfo",
                        "customerContext", "fraudScore", "riskLevel", "scoringStrategy", "modelName", "modelVersion",
                        "inferenceTimestamp", "reasonCodes", "scoreDetails", "featureSnapshot", "alertRecommended",
                        "scoringEvidence", "engineIntelligence", "analystRecommendation"
                );
    }

    @Test
    void existingScoredEventSerializationWithoutEngineIntelligenceRemainsStable() throws Exception {
        assertThat(objectMapper().writeValueAsString(oldConstructorEvent()))
                .doesNotContain("engineIntelligence", "analystRecommendation");
    }

    @Test
    void newEngineIntelligenceFieldIsOptional() {
        assertThat(oldConstructorEvent().engineIntelligence()).isNull();
    }

    @Test
    void newAnalystRecommendationFieldIsOptional() {
        assertThat(oldConstructorEvent().analystRecommendation()).isNull();
    }

    private TransactionScoredEvent oldConstructorEvent() {
        return new TransactionScoredEvent(
                "evt-1", "txn-1", "corr-1", "cust-1", "acct-1",
                Instant.parse("2026-06-01T06:00:00Z"), Instant.parse("2026-06-01T06:00:00Z"),
                null, null, null, null, null, 0.82d, RiskLevel.HIGH, "RULE_BASED", "rule-based-engine", "v2",
                Instant.parse("2026-06-01T06:00:01Z"), List.of("HIGH_VELOCITY"), Map.of(), Map.of(), true
        );
    }

    private ObjectMapper objectMapper() {
        return tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
    }

    private String currentJson() {
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
                  "modelName": "rule-based-engine",
                  "modelVersion": "v2",
                  "inferenceTimestamp": "2026-06-01T06:00:01Z",
                  "reasonCodes": ["HIGH_VELOCITY"],
                  "scoreDetails": {},
                  "featureSnapshot": {},
                  "alertRecommended": true
                }
                """;
    }

    private String newJson(String futureField) {
        return currentJson().replace(
                "\"alertRecommended\": true",
                """
                "alertRecommended": true,
                "engineIntelligence": {
                  "contractVersion": 1,
                  "generatedAt": "2026-06-01T06:00:00Z",
                  "engines": [
                    {
                      "engineId": "rules.primary",
                      "engineType": "RULES",
                      "status": "AVAILABLE",
                      "riskLevel": "HIGH",
                      "scoreBucket": "HIGH",
                      "reasonCodes": ["HIGH_VELOCITY"]
                    },
                    {
                      "engineId": "ml.python.primary",
                      "engineType": "ML_MODEL",
                      "status": "TIMEOUT",
                      "scoreBucket": "UNAVAILABLE",
                      "reasonCodes": ["ML_MODEL_TIMEOUT"]
                    }
                  ],
                  "comparison": {
                    "comparisonType": "RULES_VS_ML",
                    "comparedEngineIds": ["rules.primary", "ml.python.primary"],
                    "agreementStatus": "PARTIAL",
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

    private String newJsonWithAnalystRecommendation() {
        return currentJson().replace(
                "\"alertRecommended\": true",
                """
                "alertRecommended": true,
                "analystRecommendation": {
                  "status": "AVAILABLE",
                  "recommendation": "RECOMMEND_REVIEW",
                  "recommendationVersion": "analyst-recommendation-v1",
                  "generatedAt": "2026-06-19T10:00:00Z",
                  "confidence": "MEDIUM",
                  "source": "RULES_RISK",
                  "reasonCodes": ["RULES_HIGH_RISK"],
                  "warnings": [],
                  "nonDecisioning": {
                    "notPaymentAuthorization": true,
                    "notAutomaticDecisioning": true,
                    "notCaseAction": true,
                    "notWorkflowAction": true,
                    "notModelPromotion": true,
                    "notThresholdRecommendation": true
                  }
                }
                """
        );
    }

    private String newJsonWithMlIdentity() {
        return currentJson()
                .replace("\"modelVersion\": \"v2\"", "\"modelVersion\": \"final-score-v2\"")
                .replace(
                "\"alertRecommended\": true",
                """
                "alertRecommended": true,
                "engineIntelligence": {
                  "contractVersion": 1,
                  "generatedAt": "2026-06-01T06:00:00Z",
                  "engines": [
                    {
                      "engineId": "rules.primary",
                      "engineType": "RULES",
                      "status": "AVAILABLE",
                      "riskLevel": "HIGH",
                      "scoreBucket": "HIGH",
                      "reasonCodes": ["HIGH_VELOCITY"]
                    },
                    {
                      "engineId": "ml.python.primary",
                      "engineType": "ML_MODEL",
                      "status": "AVAILABLE",
                      "riskLevel": "HIGH",
                      "scoreBucket": "HIGH",
                      "reasonCodes": ["MODEL_HIGH_RISK"],
                      "modelIdentity": {
                        "modelName": "python-logistic-fraud-model",
                        "modelVersion": "ml-shadow-2026-06-01",
                        "featureContractVersion": "2026-05-30.feature-contract.v1"
                      }
                    }
                  ],
                  "comparison": {
                    "comparisonType": "RULES_VS_ML",
                    "comparedEngineIds": ["rules.primary", "ml.python.primary"],
                    "agreementStatus": "AGREEMENT",
                    "riskMismatchStatus": "SAME_RISK_LEVEL",
                    "scoreDeltaBucket": "NONE"
                  },
                  "diagnosticSignals": [],
                  "warnings": []
                }
                """
        );
    }
}
