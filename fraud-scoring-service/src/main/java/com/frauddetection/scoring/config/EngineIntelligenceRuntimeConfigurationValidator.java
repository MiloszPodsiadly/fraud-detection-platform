package com.frauddetection.scoring.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

public final class EngineIntelligenceRuntimeConfigurationValidator implements InitializingBean {
    private final EngineIntelligenceEmissionProperties emissionProperties;
    private final VelocityEngineProperties velocityEngineProperties;

    public EngineIntelligenceRuntimeConfigurationValidator(
            EngineIntelligenceEmissionProperties emissionProperties,
            VelocityEngineProperties velocityEngineProperties
    ) {
        this.emissionProperties = Objects.requireNonNull(emissionProperties, "emissionProperties is required");
        this.velocityEngineProperties = Objects.requireNonNull(velocityEngineProperties, "velocityEngineProperties is required");
    }

    @Override
    public void afterPropertiesSet() {
        if (!emissionProperties.emitEnabled() && velocityEngineProperties.enabled()) {
            throw new IllegalStateException(
                    EngineIntelligenceEmissionProperties.PROPERTY_NAME
                            + " must be true when "
                            + VelocityEngineProperties.PROPERTY_NAME
                            + " is true"
            );
        }
    }
}
