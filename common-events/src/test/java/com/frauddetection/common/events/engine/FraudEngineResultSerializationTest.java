package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.enums.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineResultSerializationTest {

    @Test
    void serializesRulesEngineResult() throws Exception {
        FraudEngineResult result = new FraudEngineResult(
                "rules-v1",
                FraudEngineType.RULES,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.64d,
                RiskLevel.MEDIUM,
                FraudEngineConfidence.HIGH,
                List.of("RAPID_TRANSFER_BURST"),
                List.of(new FraudEngineContribution(
                        "transferCountWindow",
                        "elevated",
                        0.31d,
                        FraudEngineContributionDirection.INCREASES_RISK
                )),
                List.of(),
                4L,
                null,
                null,
                null,
                Instant.parse("2026-05-25T09:00:00Z")
        );

        String json = objectMapper().writeValueAsString(result);

        assertThat(json)
                .contains("\"engineId\":\"rules-v1\"")
                .contains("\"engineType\":\"RULES\"")
                .contains("\"status\":\"AVAILABLE\"")
                .contains("\"score\":0.64")
                .contains("\"riskLevel\":\"MEDIUM\"")
                .contains("\"reasonCodes\":[\"RAPID_TRANSFER_BURST\"]")
                .contains("\"generatedAt\":");
        assertThat(objectMapper().readValue(json, FraudEngineResult.class).generatedAt())
                .isEqualTo(Instant.parse("2026-05-25T09:00:00Z"));
    }

    @Test
    void optionalFieldsUseNullForAbsence() throws Exception {
        FraudEngineResult result = new FraudEngineResult(
                "velocity-v1",
                FraudEngineType.VELOCITY,
                "java",
                FraudEngineStatus.AVAILABLE,
                0.42d,
                RiskLevel.LOW,
                FraudEngineConfidence.LOW,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-25T09:00:00Z")
        );

        FraudEngineResult restored = objectMapper().readValue(
                objectMapper().writeValueAsString(result),
                FraudEngineResult.class
        );

        assertThat(restored.reasonCodes()).isEmpty();
        assertThat(restored.contributions()).isEmpty();
        assertThat(restored.evidence()).isEmpty();
        assertThat(restored.modelName()).isNull();
        assertThat(restored.modelVersion()).isNull();
        assertThat(restored.fallbackReason()).isNull();
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
