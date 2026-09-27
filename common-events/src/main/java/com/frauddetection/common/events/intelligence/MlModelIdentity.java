package com.frauddetection.common.events.intelligence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.frauddetection.common.events.ml.MlModelIdentityPolicy;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MlModelIdentity(
        String modelName,
        String modelVersion,
        String featureContractVersion
) {
    public MlModelIdentity {
        modelName = MlModelIdentityPolicy.requireModelName(
                modelName,
                "modelName"
        );
        modelVersion = MlModelIdentityPolicy.requireModelVersion(
                modelVersion,
                "modelVersion"
        );
        featureContractVersion = MlModelIdentityPolicy.requireFeatureContractVersion(
                featureContractVersion,
                "featureContractVersion"
        );
    }
}
