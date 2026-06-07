package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineResultSerializationTest {

    @Test
    void serializesFullFraudEngineResultWithCompatibleFieldNames() throws Exception {
        String json = objectMapper().writeValueAsString(fullAvailableResult());

        assertThat(json)
                .contains("\"engineId\":\"rules.primary\"")
                .contains("\"engineType\":\"RULES\"")
                .contains("\"engineLanguage\":\"java\"")
                .contains("\"status\":\"AVAILABLE\"")
                .contains("\"score\":0.64")
                .contains("\"riskLevel\":\"MEDIUM\"")
                .contains("\"confidence\":\"HIGH\"")
                .contains("\"reasonCodes\":[\"HIGH_VELOCITY\"]")
                .contains("\"feature\":\"TRANSFER_COUNT\"")
                .contains("\"reasonCode\":\"HIGH_VELOCITY\"")
                .contains("\"statusReason\":null")
                .doesNotContain("\"fallbackReason\"")
                .contains("\"generatedAt\":");
    }

    @Test
    void deserializesFullFraudEngineResult() throws Exception {
        FraudEngineResult result = objectMapper().readValue(objectMapper().writeValueAsString(fullAvailableResult()),
                FraudEngineResult.class);

        assertThat(result.score()).isEqualTo(0.64d);
        assertThat(result.contributions()).hasSize(1);
        assertThat(result.contributions().getFirst().featureCode()).isEqualTo("TRANSFER_COUNT");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().getFirst().evidenceCode()).isEqualTo("HIGH_VELOCITY");
    }

    @Test
    void fallbackReasonAliasDeserializesToStatusReason() throws Exception {
        FraudEngineResult result = objectMapper().readValue("""
                {
                  "engineId": "rules.primary",
                  "engineType": "RULES",
                  "engineLanguage": "java",
                  "status": "FALLBACK_USED",
                  "score": 0.5000,
                  "riskLevel": "MEDIUM",
                  "confidence": "UNKNOWN",
                  "reasonCodes": ["FALLBACK_PATH_USED"],
                  "fallbackReason": "RULE_ENGINE_FALLBACK",
                  "generatedAt": "2026-06-01T10:15:30Z"
                }
                """, FraudEngineResult.class);

        assertThat(result.statusReason()).isEqualTo("RULE_ENGINE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("RULE_ENGINE_FALLBACK");
    }

    @Test
    void deserializesOperationalUnavailableResultWithoutScoreRisk() throws Exception {
        FraudEngineResult result = objectMapper().readValue("""
                {
                  "engineId": "ml.python.primary",
                  "engineType": "ML_MODEL",
                  "engineLanguage": "python",
                  "status": "UNAVAILABLE",
                  "confidence": "UNKNOWN",
                  "reasonCodes": ["MODEL_RUNTIME_UNAVAILABLE"],
                  "statusReason": "MODEL_RUNTIME_UNAVAILABLE",
                  "generatedAt": "2026-06-01T10:15:30Z"
                }
                """, FraudEngineResult.class);

        assertThat(result.score()).isNull();
        assertThat(result.riskLevel()).isNull();
        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
    }

    @Test
    void unknownOptionalFieldsDoNotBreakDeserialization() throws Exception {
        FraudEngineResult result = objectMapper().readValue("""
                {
                  "engineId": "rules.primary",
                  "engineType": "RULES",
                  "engineLanguage": "java",
                  "status": "AVAILABLE",
                  "score": 0.7000,
                  "riskLevel": "HIGH",
                  "confidence": "MEDIUM",
                  "generatedAt": "2026-06-01T10:15:30Z",
                  "futureField": "ignored",
                  "contributions": [
                    {
                      "feature": "TRANSFER_COUNT",
                      "direction": "INCREASES_RISK",
                      "futureNestedField": true
                    }
                  ]
                }
                """, FraudEngineResult.class);

        assertThat(result.engineId()).isEqualTo("rules.primary");
        assertThat(result.contributions()).hasSize(1);
    }

    @Test
    void missingOptionalFieldsAreAllowedAndListsBecomeEmpty() throws Exception {
        FraudEngineResult result = objectMapper().readValue("""
                {
                  "engineId": "rules.primary",
                  "engineType": "RULES",
                  "engineLanguage": "java",
                  "status": "AVAILABLE",
                  "score": 0.7000,
                  "riskLevel": "HIGH",
                  "confidence": "MEDIUM",
                  "generatedAt": "2026-06-01T10:15:30Z"
                }
                """, FraudEngineResult.class);

        assertThat(result.confidence()).isEqualTo(FraudEngineConfidence.MEDIUM);
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(result.contributions()).isEmpty();
        assertThat(result.evidence()).isEmpty();
    }

    static FraudEngineResult fullAvailableResult() {
        return new FraudEngineResult(
                "rules.primary",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.6400d,
                RiskLevel.MEDIUM,
                FraudEngineConfidence.HIGH,
                List.of("HIGH_VELOCITY"),
                List.of(new FraudEngineContribution(
                        "TRANSFER_COUNT",
                        "HIGH",
                        0.3100d,
                        FraudEngineContributionDirection.INCREASES_RISK
                )),
                List.of(new FraudEngineEvidence(
                        FraudEngineEvidenceType.RULE_MATCH,
                        "HIGH_VELOCITY",
                        "High velocity",
                        "Bounded rule scoring signal.",
                        "RULES",
                        FraudEngineEvidenceStatus.AVAILABLE
                )),
                4L,
                null,
                null,
                null,
                generatedAt()
        );
    }

    static Instant generatedAt() {
        return Instant.parse("2026-06-01T10:15:30Z");
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
