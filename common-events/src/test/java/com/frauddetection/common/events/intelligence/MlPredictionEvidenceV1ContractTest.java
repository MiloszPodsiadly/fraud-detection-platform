package com.frauddetection.common.events.intelligence;

import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlPredictionEvidenceV1ContractTest {

    private static final Instant EXECUTED_AT = Instant.parse("2026-10-03T10:15:30.123456Z");
    private static final MlModelIdentity MODEL_IDENTITY = new MlModelIdentity(
            "python-logistic-fraud-model",
            "2026-05-30.v1",
            "2026-05-30.feature-contract.v1"
    );

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void canonicalAvailablePredictionPreservesExactBoundedScoreIdentityAndTimestamp() {
        MlPredictionEvidenceV1 evidence = validEvidence(0.1234d);

        assertThat(evidence.contractVersion()).isEqualTo(MlPredictionEvidenceV1.CONTRACT_VERSION);
        assertThat(evidence.sourceEngineId())
                .isEqualTo(FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID);
        assertThat(evidence.engineStatus()).isEqualTo(FraudEngineStatus.AVAILABLE);
        assertThat(evidence.mlScore()).isEqualTo(0.1234d);
        assertThat(evidence.mlRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(evidence.modelIdentity()).isEqualTo(MODEL_IDENTITY);
        assertThat(evidence.sourceExecutionTimestamp()).isEqualTo(EXECUTED_AT);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0d, 1.0d, 0.0001d, 0.9999d})
    void acceptsScoresWithinCanonicalRangeAndPrecision(double score) {
        assertThat(validEvidence(score).mlScore()).isEqualTo(score);
    }

    @Test
    void serializesAndDeserializesCanonicalFlatContract() throws Exception {
        MlPredictionEvidenceV1 evidence = validEvidence(0.8123d);

        String json = objectMapper.writeValueAsString(evidence);
        MlPredictionEvidenceV1 roundTrip = objectMapper.readValue(json, MlPredictionEvidenceV1.class);

        assertThat(roundTrip).isEqualTo(evidence);
        assertThat(json).contains(
                "\"contractVersion\":1",
                "\"sourceEngineId\":\"ml.python.primary\"",
                "\"engineStatus\":\"AVAILABLE\"",
                "\"mlScore\":0.8123",
                "\"mlRiskLevel\":\"HIGH\"",
                "\"modelName\":\"python-logistic-fraud-model\"",
                "\"modelVersion\":\"2026-05-30.v1\"",
                "\"featureContractVersion\":\"2026-05-30.feature-contract.v1\"",
                "\"sourceExecutionTimestamp\":\"2026-10-03T10:15:30.123456Z\""
        ).doesNotContain("\"modelIdentity\"");
    }

    @Test
    void ignoresUnknownFieldsWithoutWeakeningKnownFieldValidation() throws Exception {
        String json = objectMapper.writeValueAsString(validEvidence(0.8123d));
        String withUnknown = json.substring(0, json.length() - 1) + ",\"futureField\":\"ignored\"}";

        assertThat(objectMapper.readValue(withUnknown, MlPredictionEvidenceV1.class))
                .isEqualTo(validEvidence(0.8123d));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 99})
    void rejectsUnsupportedContractVersion(int contractVersion) {
        assertThatThrownBy(() -> evidence(
                contractVersion,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                FraudEngineStatus.AVAILABLE,
                0.5d,
                RiskLevel.MEDIUM,
                MODEL_IDENTITY.modelName(),
                MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion(),
                EXECUTED_AT
        )).hasMessage("ML_PREDICTION_EVIDENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    @Test
    void rejectsMissingContractVersionDuringDeserialization() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"sourceEngineId\":\"ml.python.primary\"}",
                MlPredictionEvidenceV1.class
        )).hasRootCauseMessage("ML_PREDICTION_EVIDENCE_UNSUPPORTED_CONTRACT_VERSION");
    }

    @Test
    void rejectsMalformedSourceExecutionTimestampDuringDeserialization() throws Exception {
        String json = objectMapper.writeValueAsString(validEvidence(0.8123d))
                .replace("2026-10-03T10:15:30.123456Z", "not-an-instant");

        assertThatThrownBy(() -> objectMapper.readValue(json, MlPredictionEvidenceV1.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsNonCanonicalOrMissingSourceEngine() {
        for (String sourceEngineId : new String[]{null, "rules.primary", "ml.python.secondary"}) {
            assertThatThrownBy(() -> evidenceWithSource(sourceEngineId))
                    .hasMessage("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INVALID");
        }
    }

    @ParameterizedTest
    @EnumSource(value = FraudEngineStatus.class, names = "AVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void rejectsOperationalStatusesInsteadOfManufacturingPrediction(FraudEngineStatus status) {
        assertThatThrownBy(() -> evidenceWithStatus(status))
                .hasMessage("ML_PREDICTION_EVIDENCE_AVAILABLE_STATUS_REQUIRED");
    }

    @Test
    void rejectsMissingStatus() {
        assertThatThrownBy(() -> evidenceWithStatus(null))
                .hasMessage("ML_PREDICTION_EVIDENCE_AVAILABLE_STATUS_REQUIRED");
    }

    @Test
    void rejectsMissingNonFiniteOutOfRangeAndOverPrecisionScores() {
        for (Double score : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -0.0001d, 1.0001d, 0.12345d}) {
            assertThatThrownBy(() -> evidenceWithScore(score)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsMissingRiskLevelTimestampAndModelIdentity() {
        assertThatThrownBy(() -> evidenceWithRiskLevel(null)).hasMessage("mlRiskLevel is required");
        assertThatThrownBy(() -> evidenceWithTimestamp(null)).hasMessage("sourceExecutionTimestamp is required");
        assertThatThrownBy(() -> new MlPredictionEvidenceV1(0.5d, RiskLevel.MEDIUM, null, EXECUTED_AT))
                .hasMessage("modelIdentity is required");
    }

    @Test
    void rejectsPartialOrUnsafeModelIdentityThroughCanonicalIdentityPolicy() {
        assertThatThrownBy(() -> evidenceWithIdentity(null, MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion())).hasMessageContaining("modelName");
        assertThatThrownBy(() -> evidenceWithIdentity(MODEL_IDENTITY.modelName(), null,
                MODEL_IDENTITY.featureContractVersion())).hasMessageContaining("modelVersion");
        assertThatThrownBy(() -> evidenceWithIdentity(MODEL_IDENTITY.modelName(), MODEL_IDENTITY.modelVersion(), null))
                .hasMessageContaining("featureContractVersion");
        assertThatThrownBy(() -> evidenceWithIdentity("raw-payload-model", MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion())).hasMessageContaining("modelName");
    }

    @Test
    void contractContainsNoArtifactHashRawPayloadMetadataOrProbabilityClaim() {
        assertThat(Arrays.stream(MlPredictionEvidenceV1.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain(
                        "modelArtifactSha256",
                        "rawFeatures",
                        "rawRequest",
                        "rawResponse",
                        "metadata",
                        "customerId",
                        "calibratedProbability",
                        "probability"
                );
    }

    private MlPredictionEvidenceV1 validEvidence(double score) {
        return new MlPredictionEvidenceV1(score, RiskLevel.HIGH, MODEL_IDENTITY, EXECUTED_AT);
    }

    private MlPredictionEvidenceV1 evidenceWithSource(String sourceEngineId) {
        return evidence(MlPredictionEvidenceV1.CONTRACT_VERSION, sourceEngineId, FraudEngineStatus.AVAILABLE,
                0.5d, RiskLevel.MEDIUM, MODEL_IDENTITY.modelName(), MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion(), EXECUTED_AT);
    }

    private MlPredictionEvidenceV1 evidenceWithStatus(FraudEngineStatus status) {
        return evidence(MlPredictionEvidenceV1.CONTRACT_VERSION,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, status, 0.5d, RiskLevel.MEDIUM,
                MODEL_IDENTITY.modelName(), MODEL_IDENTITY.modelVersion(), MODEL_IDENTITY.featureContractVersion(),
                EXECUTED_AT);
    }

    private MlPredictionEvidenceV1 evidenceWithScore(Double score) {
        return evidence(MlPredictionEvidenceV1.CONTRACT_VERSION,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, FraudEngineStatus.AVAILABLE, score,
                RiskLevel.MEDIUM, MODEL_IDENTITY.modelName(), MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion(), EXECUTED_AT);
    }

    private MlPredictionEvidenceV1 evidenceWithRiskLevel(RiskLevel riskLevel) {
        return evidence(MlPredictionEvidenceV1.CONTRACT_VERSION,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, FraudEngineStatus.AVAILABLE, 0.5d,
                riskLevel, MODEL_IDENTITY.modelName(), MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion(), EXECUTED_AT);
    }

    private MlPredictionEvidenceV1 evidenceWithTimestamp(Instant timestamp) {
        return evidence(MlPredictionEvidenceV1.CONTRACT_VERSION,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, FraudEngineStatus.AVAILABLE, 0.5d,
                RiskLevel.MEDIUM, MODEL_IDENTITY.modelName(), MODEL_IDENTITY.modelVersion(),
                MODEL_IDENTITY.featureContractVersion(), timestamp);
    }

    private MlPredictionEvidenceV1 evidenceWithIdentity(
            String modelName,
            String modelVersion,
            String featureContractVersion
    ) {
        return evidence(MlPredictionEvidenceV1.CONTRACT_VERSION,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID, FraudEngineStatus.AVAILABLE, 0.5d,
                RiskLevel.MEDIUM, modelName, modelVersion, featureContractVersion, EXECUTED_AT);
    }

    private MlPredictionEvidenceV1 evidence(
            int contractVersion,
            String sourceEngineId,
            FraudEngineStatus engineStatus,
            Double mlScore,
            RiskLevel mlRiskLevel,
            String modelName,
            String modelVersion,
            String featureContractVersion,
            Instant sourceExecutionTimestamp
    ) {
        return new MlPredictionEvidenceV1(
                contractVersion,
                sourceEngineId,
                engineStatus,
                mlScore,
                mlRiskLevel,
                modelName,
                modelVersion,
                featureContractVersion,
                sourceExecutionTimestamp
        );
    }
}
