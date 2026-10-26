package com.frauddetection.scoring.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

public final class EngineIntelligenceRuntimeConfigurationValidator implements InitializingBean {
    private final EngineIntelligenceEmissionProperties emissionProperties;
    private final VelocityEngineProperties velocityEngineProperties;
    private final DeviceRiskEngineProperties deviceRiskEngineProperties;

    public EngineIntelligenceRuntimeConfigurationValidator(
            EngineIntelligenceEmissionProperties emissionProperties,
            VelocityEngineProperties velocityEngineProperties,
            DeviceRiskEngineProperties deviceRiskEngineProperties
    ) {
        this.emissionProperties = Objects.requireNonNull(emissionProperties, "emissionProperties is required");
        this.velocityEngineProperties = Objects.requireNonNull(velocityEngineProperties, "velocityEngineProperties is required");
        this.deviceRiskEngineProperties = Objects.requireNonNull(deviceRiskEngineProperties,
                "deviceRiskEngineProperties is required");
    }

    @Override
    public void afterPropertiesSet() {
        if (deviceRiskEngineProperties.enabled()) {
            throw new IllegalStateException(DeviceRiskEngineProperties.PROPERTY_NAME
                    + " requires an authenticated occurrence-bound reader and a migrated public contract");
        }
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
