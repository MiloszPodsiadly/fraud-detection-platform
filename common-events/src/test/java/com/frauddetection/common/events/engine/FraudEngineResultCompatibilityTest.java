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
    void unknownJsonFieldIsRejectedUntilCompatibilityPolicyChanges() throws Exception {
        String json = Files.readString(example("rules-engine-result.json"));
        int end = json.lastIndexOf('}');
        String withUnknownField = json.substring(0, end)
                + ",\n  \"unknownField\": \"future-value\"\n"
                + json.substring(end);

        assertThatThrownBy(() -> objectMapper().readValue(withUnknownField, FraudEngineResult.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("unknownField");
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
