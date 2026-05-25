package com.frauddetection.common.events.engine;

import com.frauddetection.common.events.enums.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineResultCompatibilityTest {

    @Test
    void deserializesPythonMlEngineResult() throws Exception {
        FraudEngineResult result = objectMapper().readValue(
                Files.readString(example("python-ml-engine-result.json")),
                FraudEngineResult.class
        );

        assertThat(result.engineType()).isEqualTo(FraudEngineType.ML_MODEL);
        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(result.score()).isEqualTo(0.82d);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.reasonCodes()).containsExactly("rapidTransferBurst", "deviceNovelty");
        assertThat(result.modelName()).isEqualTo("python-logistic-fraud-model");
        assertThat(result.modelVersion()).isEqualTo("v1");
        assertThat(result.latencyMs()).isEqualTo(28L);
    }

    @Test
    void additiveUnknownTopLevelFieldsAreIgnored() throws Exception {
        String json = Files.readString(example("rules-engine-result.json"));
        int end = json.lastIndexOf('}');
        String withUnknownField = json.substring(0, end)
                + ",\n  \"unknownField\": \"future-value\"\n"
                + json.substring(end);

        FraudEngineResult result = objectMapper().readValue(withUnknownField, FraudEngineResult.class);

        assertThat(result.engineId()).isEqualTo("rules-v1");
        assertThat(result.status()).isEqualTo(FraudEngineStatus.AVAILABLE);
    }

    @Test
    void additiveUnknownNestedFieldsAreIgnored() throws Exception {
        String json = Files.readString(example("rules-engine-result.json"))
                .replace("\"direction\": \"INCREASES_RISK\"", "\"direction\": \"INCREASES_RISK\", \"unknownContribution\": true")
                .replace("\"status\": \"AVAILABLE\"\n    }", "\"status\": \"AVAILABLE\", \"unknownEvidence\": true\n    }");

        FraudEngineResult result = objectMapper().readValue(json, FraudEngineResult.class);

        assertThat(result.contributions()).hasSize(1);
        assertThat(result.evidence()).hasSize(1);
    }

    @Test
    void additiveUnknownFieldsDoNotRelaxKnownFieldValidation() throws Exception {
        String invalidJson = Files.readString(example("rules-engine-result.json"))
                .replace("\"score\": 0.64", "\"score\": 1.64")
                .replace("\"engineLanguage\": \"java\"", "\"engineLanguage\": \"java\", \"unknownField\": true");

        assertThatThrownBy(() -> objectMapper().readValue(invalidJson, FraudEngineResult.class))
                .hasMessageContaining("score");
    }

    private Path example(String filename) {
        Path moduleRelative = Path.of("..", "docs", "examples", "fraud-engine-result", filename);
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Path.of("docs", "examples", "fraud-engine-result", filename);
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
