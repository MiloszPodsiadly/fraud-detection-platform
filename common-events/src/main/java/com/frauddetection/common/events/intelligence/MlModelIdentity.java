package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MlModelIdentity(
        String modelName,
        String modelVersion,
        String featureContractVersion
) {
    public MlModelIdentity {
        modelName = EngineIntelligenceValuePolicy.requireBoundedSafeText(
                modelName,
                "ENGINE_INTELLIGENCE_MODEL_NAME_INVALID"
        );
        modelVersion = EngineIntelligenceValuePolicy.requireBoundedSafeText(
                modelVersion,
                "ENGINE_INTELLIGENCE_MODEL_VERSION_INVALID"
        );
        featureContractVersion = EngineIntelligenceValuePolicy.requireBoundedSafeText(
                featureContractVersion,
                "ENGINE_INTELLIGENCE_FEATURE_CONTRACT_VERSION_INVALID"
        );
    }
}
