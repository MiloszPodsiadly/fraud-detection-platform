package com.frauddetection.alert.feedback.dataset.evidence;

import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjection;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;

import java.time.Instant;
import java.util.Objects;

public record FeedbackDatasetMlPredictionEvidence(
        Double mlScore,
        RiskLevel mlRiskLevel,
        Instant sourceExecutionTimestamp,
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String modelArtifactSha256
) {
    public FeedbackDatasetMlPredictionEvidence {
        MlPredictionEvidence validated = new MlPredictionEvidence(
                mlScore,
                mlRiskLevel,
                modelName,
                modelVersion,
                featureContractVersion,
                modelArtifactSha256,
                sourceExecutionTimestamp
        );
        mlScore = validated.mlScore();
        mlRiskLevel = validated.mlRiskLevel();
        sourceExecutionTimestamp = validated.sourceExecutionTimestamp();
        modelName = validated.modelName();
        modelVersion = validated.modelVersion();
        featureContractVersion = validated.featureContractVersion();
        modelArtifactSha256 = validated.modelArtifactSha256();
    }

    static FeedbackDatasetMlPredictionEvidence from(MlPredictionEvidenceProjection projection) {
        MlPredictionEvidenceProjection source = Objects.requireNonNull(projection, "projection is required");
        return new FeedbackDatasetMlPredictionEvidence(
                source.getMlScore(),
                source.getMlRiskLevel(),
                source.getSourceExecutionTimestamp(),
                source.getModelName(),
                source.getModelVersion(),
                source.getFeatureContractVersion(),
                source.getModelArtifactSha256()
        );
    }
}
