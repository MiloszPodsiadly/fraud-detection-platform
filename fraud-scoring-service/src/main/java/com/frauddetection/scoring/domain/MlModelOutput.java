package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.ml.MlModelIdentityPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MlModelOutput(
        boolean available,
        Double fraudScore,
        RiskLevel riskLevel,
        String modelName,
        String modelVersion,
        String featureContractVersion,
        String modelArtifactSha256,
        Instant inferenceTimestamp,
        List<String> reasonCodes,
        Map<String, Object> scoreDetails,
        Map<String, Object> explanationMetadata,
        String fallbackReason
) {
    public MlModelOutput {
        modelName = MlModelIdentityPolicy.optionalModelName(modelName, "modelName");
        modelVersion = MlModelIdentityPolicy.optionalModelVersion(modelVersion, "modelVersion");
        featureContractVersion = MlModelIdentityPolicy.optionalFeatureContractVersion(
                featureContractVersion,
                "featureContractVersion"
        );
        modelArtifactSha256 = MlModelIdentityPolicy.optionalArtifactSha256(
                modelArtifactSha256,
                "modelArtifactSha256"
        );
        MlModelIdentityPolicy.requireAtomicArtifactIdentity(
                modelName,
                modelVersion,
                featureContractVersion,
                modelArtifactSha256
        );
    }

    public MlModelOutput(
            boolean available,
            Double fraudScore,
            RiskLevel riskLevel,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> explanationMetadata,
            String fallbackReason
    ) {
        this(
                available,
                fraudScore,
                riskLevel,
                modelName,
                modelVersion,
                null,
                null,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                explanationMetadata,
                fallbackReason
        );
    }

}
