package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudEngineResultExamplePayloadTest {

    private static final List<String> EXAMPLES = List.of(
            "rules-engine-result.json",
            "python-ml-engine-result.json",
            "unavailable-ml-engine-result.json",
            "degraded-engine-result.json"
    );

    @Test
    void contractExamplesMatchModelAndDoNotContainRawSensitiveFields() throws Exception {
        for (String filename : EXAMPLES) {
            String json = Files.readString(example(filename));
            FraudEngineResult result = objectMapper().readValue(json, FraudEngineResult.class);

            assertThat(result.engineId()).as(filename).isNotBlank();
            assertThat(result.generatedAt()).as(filename).isNotNull();
            assertThat(json.toLowerCase())
                    .as(filename)
                    .doesNotContain(
                            "\"rawpayload\"",
                            "\"rawfeatures\"",
                            "\"stacktrace\"",
                            "\"exception\"",
                            "\"token\"",
                            "\"secret\"",
                            "\"customerpayload\""
                    );
        }
    }

    @Test
    void examplesShowAvailableUnavailableAndDegradedStates() throws Exception {
        assertThat(read("rules-engine-result.json").status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(read("python-ml-engine-result.json").status()).isEqualTo(FraudEngineStatus.AVAILABLE);

        FraudEngineResult unavailable = read("unavailable-ml-engine-result.json");
        assertThat(unavailable.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(unavailable.score()).isNull();
        assertThat(unavailable.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);
        assertThat(unavailable.fallbackReason()).isEqualTo("MODEL_RUNTIME_UNAVAILABLE");

        assertThat(read("degraded-engine-result.json").status()).isEqualTo(FraudEngineStatus.DEGRADED);
    }

    @Test
    void mutatedExamplesFailKnownFieldSafetyRules() throws Exception {
        String rules = Files.readString(example("rules-engine-result.json"));
        String invalidReasonCode = rules.replace("RAPID_TRANSFER_BURST", "RAW CUSTOMER VALUE");
        String contradictoryStatus = rules.replace("\"status\": \"AVAILABLE\"", "\"status\": \"UNAVAILABLE\"");
        String invalidEvidenceType = rules.replace("\"evidenceType\": \"VELOCITY_SIGNAL\"", "\"evidenceType\": \"FREE_TEXT\"");

        assertThatThrownBy(() -> objectMapper().readValue(invalidReasonCode, FraudEngineResult.class))
                .hasMessageContaining("reasonCode");
        assertThatThrownBy(() -> objectMapper().readValue(contradictoryStatus, FraudEngineResult.class))
                .hasMessageContaining("UNAVAILABLE");
        assertThatThrownBy(() -> objectMapper().readValue(invalidEvidenceType, FraudEngineResult.class))
                .hasMessageContaining("FREE_TEXT");
    }

    private FraudEngineResult read(String filename) throws Exception {
        return objectMapper().readValue(Files.readString(example(filename)), FraudEngineResult.class);
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
