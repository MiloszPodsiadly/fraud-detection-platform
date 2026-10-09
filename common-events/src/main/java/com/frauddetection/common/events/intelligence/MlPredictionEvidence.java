package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.frauddetection.common.events.engine.FraudEngineScorePolicy;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.ml.MlModelIdentityPolicy;

import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MlPredictionEvidence(
        Double mlScore,
        RiskLevel mlRiskLevel,
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String modelArtifactSha256,
        Instant sourceExecutionTimestamp
) {
    public MlPredictionEvidence {
        mlScore = FraudEngineScorePolicy.requireValid(mlScore, "mlScore");
        Objects.requireNonNull(mlRiskLevel, "mlRiskLevel is required");
        MlModelIdentity identity = new MlModelIdentity(modelName, modelVersion, featureContractVersion);
        modelName = identity.modelName();
        modelVersion = identity.modelVersion();
        featureContractVersion = identity.featureContractVersion();
        modelArtifactSha256 = MlModelIdentityPolicy.requireArtifactSha256(
                modelArtifactSha256,
                "modelArtifactSha256"
        );
        Objects.requireNonNull(sourceExecutionTimestamp, "sourceExecutionTimestamp is required");
    }

    public MlPredictionEvidence(
            double mlScore,
            RiskLevel mlRiskLevel,
            MlModelIdentity modelIdentity,
            String modelArtifactSha256,
            Instant sourceExecutionTimestamp
    ) {
        this(
                mlScore,
                mlRiskLevel,
                requireIdentity(modelIdentity).modelName(),
                modelIdentity.modelVersion(),
                modelIdentity.featureContractVersion(),
                modelArtifactSha256,
                sourceExecutionTimestamp
        );
    }

    @JsonIgnore
    public MlModelIdentity modelIdentity() {
        return new MlModelIdentity(modelName, modelVersion, featureContractVersion);
    }

    @JsonAnySetter
    void rejectObsoleteVersionMarker(String fieldName, Object value) {
        if ("contractVersion".equals(fieldName)) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_OBSOLETE_VERSION_MARKER");
        }
    }

    private static MlModelIdentity requireIdentity(MlModelIdentity modelIdentity) {
        return Objects.requireNonNull(modelIdentity, "modelIdentity is required");
    }
}
