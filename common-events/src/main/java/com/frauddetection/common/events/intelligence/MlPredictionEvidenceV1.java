package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.events.engine.FraudEngineIdentityContract;
import com.frauddetection.common.events.engine.FraudEngineScorePolicy;
import com.frauddetection.common.events.engine.FraudEngineStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MlPredictionEvidenceV1(
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
    public static final int CONTRACT_VERSION = 1;

    @JsonCreator
    public static MlPredictionEvidenceV1 fromJson(
            @JsonProperty("contractVersion") Integer contractVersion,
            @JsonProperty("sourceEngineId") String sourceEngineId,
            @JsonProperty("engineStatus") FraudEngineStatus engineStatus,
            @JsonProperty("mlScore") Double mlScore,
            @JsonProperty("mlRiskLevel") RiskLevel mlRiskLevel,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("modelVersion") String modelVersion,
            @JsonProperty("featureContractVersion") String featureContractVersion,
            @JsonProperty("sourceExecutionTimestamp") Instant sourceExecutionTimestamp
    ) {
        return new MlPredictionEvidenceV1(
                contractVersion == null ? 0 : contractVersion,
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

    public MlPredictionEvidenceV1 {
        if (contractVersion != CONTRACT_VERSION) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_UNSUPPORTED_CONTRACT_VERSION");
        }
        if (!FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID.equals(sourceEngineId)) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_SOURCE_ENGINE_INVALID");
        }
        if (engineStatus != FraudEngineStatus.AVAILABLE) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_AVAILABLE_STATUS_REQUIRED");
        }
        mlScore = FraudEngineScorePolicy.requireValid(mlScore, "mlScore");
        Objects.requireNonNull(mlRiskLevel, "mlRiskLevel is required");
        MlModelIdentity identity = new MlModelIdentity(modelName, modelVersion, featureContractVersion);
        modelName = identity.modelName();
        modelVersion = identity.modelVersion();
        featureContractVersion = identity.featureContractVersion();
        Objects.requireNonNull(sourceExecutionTimestamp, "sourceExecutionTimestamp is required");
    }

    public MlPredictionEvidenceV1(
            double mlScore,
            RiskLevel mlRiskLevel,
            MlModelIdentity modelIdentity,
            Instant sourceExecutionTimestamp
    ) {
        this(
                CONTRACT_VERSION,
                FraudEngineIdentityContract.PYTHON_ML_PRIMARY_ENGINE_ID,
                FraudEngineStatus.AVAILABLE,
                mlScore,
                mlRiskLevel,
                requireIdentity(modelIdentity).modelName(),
                modelIdentity.modelVersion(),
                modelIdentity.featureContractVersion(),
                sourceExecutionTimestamp
        );
    }

    @JsonIgnore
    public MlModelIdentity modelIdentity() {
        return new MlModelIdentity(modelName, modelVersion, featureContractVersion);
    }

    private static MlModelIdentity requireIdentity(MlModelIdentity modelIdentity) {
        return Objects.requireNonNull(modelIdentity, "modelIdentity is required");
    }
}
