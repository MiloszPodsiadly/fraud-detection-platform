package com.frauddetection.scoring.engine;

import com.frauddetection.common.events.engine.FraudEngineType;

import java.util.Objects;

public record FraudEngineDescriptor(
        String engineId,
        FraudEngineType engineType,
        String engineLanguage,
        String version,
        boolean required
) {

    public FraudEngineDescriptor {
        engineId = FraudEngineDescriptorValuePolicy.requireMachineReadableId(engineId, "engineId");
        engineType = Objects.requireNonNull(engineType, "engineType is required");
        engineLanguage = FraudEngineDescriptorValuePolicy.requireEngineLanguage(engineLanguage);
        version = FraudEngineDescriptorValuePolicy.requireMachineReadableId(version, "version");
    }
}
