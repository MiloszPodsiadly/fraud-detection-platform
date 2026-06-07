package com.frauddetection.common.events.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FraudEngineResultExamplePayloadTest {

    private static final String ROOT = "contracts/fraud-engine-result/";
    private static final List<String> SAMPLE_FILES = List.of(
            "available-rules-engine-result.json",
            "available-ml-engine-result.json",
            "timeout-ml-engine-result.json",
            "unavailable-engine-result.json",
            "fallback-used-engine-result.json",
            "degraded-engine-result.json"
    );

    @Test
    void samplePayloadsDeserializeIntoFraudEngineResult() throws Exception {
        for (String file : SAMPLE_FILES) {
            FraudEngineResult result = read(file);

            assertThat(result.engineId()).as(file).isNotBlank();
        }
    }

    @Test
    void samplePayloadsReserializeWithoutThrowing() throws Exception {
        for (String file : SAMPLE_FILES) {
            String json = objectMapper().writeValueAsString(read(file));

            assertThat(json).as(file).contains("\"engineId\"");
        }
    }

    @Test
    void samplesRepresentRequiredStatusCases() throws Exception {
        assertThat(read("available-rules-engine-result.json").status()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(read("available-rules-engine-result.json").engineType()).isEqualTo(FraudEngineType.RULES);
        assertThat(read("available-ml-engine-result.json").engineType()).isEqualTo(FraudEngineType.ML_MODEL);
        assertThat(read("available-ml-engine-result.json").modelName()).isEqualTo("python-fraud-model");

        FraudEngineResult timeout = read("timeout-ml-engine-result.json");
        assertThat(timeout.status()).isEqualTo(FraudEngineStatus.TIMEOUT);
        assertThat(timeout.score()).isNull();
        assertThat(timeout.riskLevel()).isNull();
        assertThat(timeout.confidence()).isEqualTo(FraudEngineConfidence.UNKNOWN);

        FraudEngineResult unavailable = read("unavailable-engine-result.json");
        assertThat(unavailable.status()).isEqualTo(FraudEngineStatus.UNAVAILABLE);
        assertThat(unavailable.score()).isNull();
        assertThat(unavailable.riskLevel()).isNull();

        FraudEngineResult fallback = read("fallback-used-engine-result.json");
        assertThat(fallback.status()).isEqualTo(FraudEngineStatus.FALLBACK_USED);
        assertThat(fallback.statusReason()).isEqualTo("RULE_ENGINE_FALLBACK");
        assertThat(fallback.fallbackReason()).isEqualTo("RULE_ENGINE_FALLBACK");
        assertThat(sample("fallback-used-engine-result.json"))
                .contains("\"statusReason\"")
                .doesNotContain("\"fallbackReason\"");

        FraudEngineResult degraded = read("degraded-engine-result.json");
        assertThat(degraded.status()).isEqualTo(FraudEngineStatus.DEGRADED);
        assertThat(degraded.score()).isEqualTo(0.45d);
        assertThat(degraded.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void samplePayloadsDoNotContainForbiddenRawOrDecisioningTerms() throws Exception {
        for (String file : SAMPLE_FILES) {
            String compact = sample(file).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");

            assertThat(compact).as(file).doesNotContain(
                    "customerid",
                    "accountid",
                    "cardid",
                    "deviceid",
                    "merchantid",
                    "rawpayload",
                    "featurevector",
                    "token",
                    "secret",
                    "endpoint",
                    "stacktrace",
                    "finaldecision",
                    "approve",
                    "decline",
                    "block",
                    "recommendedaction",
                    "modeltraininglabel",
                    "groundtruth"
            );
        }
    }

    @Test
    void samplePayloadEvidenceTypesAreDeclaredEnumValues() throws Exception {
        Set<String> declaredEvidenceTypes = java.util.Arrays.stream(FraudEngineEvidenceType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        for (String file : SAMPLE_FILES) {
            for (FraudEngineEvidence evidence : read(file).evidence()) {
                assertThat(declaredEvidenceTypes).as(file).contains(evidence.evidenceType().name());
            }
        }
    }

    @Test
    void operationalOutageSamplesUseOperationalStatusEvidence() throws Exception {
        assertThat(read("timeout-ml-engine-result.json").evidence())
                .extracting(FraudEngineEvidence::evidenceType)
                .containsOnly(FraudEngineEvidenceType.OPERATIONAL_STATUS);
        assertThat(read("unavailable-engine-result.json").evidence())
                .extracting(FraudEngineEvidence::evidenceType)
                .containsOnly(FraudEngineEvidenceType.OPERATIONAL_STATUS);
        assertThat(read("degraded-engine-result.json").evidence())
                .extracting(FraudEngineEvidence::evidenceType)
                .containsOnly(FraudEngineEvidenceType.OPERATIONAL_STATUS);
    }

    private FraudEngineResult read(String file) throws Exception {
        return objectMapper().readValue(sample(file), FraudEngineResult.class);
    }

    private String sample(String file) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(ROOT + file)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing sample " + file);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
