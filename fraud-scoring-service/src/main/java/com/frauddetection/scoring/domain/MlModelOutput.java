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
        validateAtomicModelIdentity(modelName, modelVersion, featureContractVersion);
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
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                explanationMetadata,
                fallbackReason
        );
    }

    private static void validateAtomicModelIdentity(
            String modelName,
            String modelVersion,
            String featureContractVersion
    ) {
        int present = 0;
        present += modelName == null ? 0 : 1;
        present += modelVersion == null ? 0 : 1;
        present += featureContractVersion == null ? 0 : 1;
        if (present != 0 && present != 3) {
            throw new IllegalArgumentException("ML model identity must be entirely absent or complete");
        }
    }
}
