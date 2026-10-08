package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlPredictionEvidenceContractTest {

    private static final Instant EXECUTED_AT = Instant.parse("2026-10-03T10:15:30.123456Z");
    private static final String ARTIFACT_SHA256 = "a".repeat(64);
    private static final MlModelIdentity MODEL_IDENTITY = new MlModelIdentity(
            "python-logistic-fraud-model",
            "2026-05-30.v1",
            "2026-05-30.feature-contract.v1"
    );

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void preservesExactArtifactIdentityScoreRiskAndExecutionTimestamp() {
        MlPredictionEvidence evidence = validEvidence();

        assertThat(evidence.mlScore()).isEqualTo(0.8123d);
        assertThat(evidence.mlRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(evidence.modelIdentity()).isEqualTo(MODEL_IDENTITY);
        assertThat(evidence.modelArtifactSha256()).isEqualTo(ARTIFACT_SHA256);
        assertThat(evidence.sourceExecutionTimestamp()).isEqualTo(EXECUTED_AT);
    }

    @Test
    void serializesAndDeserializesCanonicalFlatContract() throws Exception {
        MlPredictionEvidence evidence = validEvidence();

        String json = objectMapper.writeValueAsString(evidence);
        MlPredictionEvidence roundTrip = objectMapper.readValue(json, MlPredictionEvidence.class);

        assertThat(roundTrip).isEqualTo(evidence);
        assertThat(json).contains(
                "\"mlScore\":0.8123",
                "\"mlRiskLevel\":\"HIGH\"",
                "\"modelName\":\"python-logistic-fraud-model\"",
                "\"modelVersion\":\"2026-05-30.v1\"",
                "\"featureContractVersion\":\"2026-05-30.feature-contract.v1\"",
                "\"modelArtifactSha256\":\"" + ARTIFACT_SHA256 + "\"",
                "\"sourceExecutionTimestamp\":\"2026-10-03T10:15:30.123456Z\""
        ).doesNotContain("contractVersion", "sourceEngineId", "engineStatus", "modelIdentity");
    }

    @Test
    void rejectsMissingOrMalformedExactArtifactIdentity() {
        assertThatThrownBy(() -> new MlPredictionEvidence(
                0.8123d,
                RiskLevel.HIGH,
                MODEL_IDENTITY,
                null,
                EXECUTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MlPredictionEvidence(
                0.8123d,
                RiskLevel.HIGH,
                MODEL_IDENTITY,
                "not-a-digest",
                EXECUTED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MlPredictionEvidence(
                0.8123d,
                RiskLevel.HIGH,
                null,
                ARTIFACT_SHA256,
                EXECUTED_AT
        )).hasMessage("modelIdentity is required");
    }

    @Test
    void contractContainsOnlyCanonicalExactEvidenceFields() {
        assertThat(Arrays.stream(MlPredictionEvidence.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly(
                        "mlScore",
                        "mlRiskLevel",
                        "modelName",
                        "modelVersion",
                        "featureContractVersion",
                        "modelArtifactSha256",
                        "sourceExecutionTimestamp"
                );
    }

    private MlPredictionEvidence validEvidence() {
        return new MlPredictionEvidence(
                0.8123d,
                RiskLevel.HIGH,
                MODEL_IDENTITY,
                ARTIFACT_SHA256,
                EXECUTED_AT
        );
    }
}
