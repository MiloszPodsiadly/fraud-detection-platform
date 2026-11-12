package com.frauddetection.enricher.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/** Protocol implementation and test trust do not authorize production activation. */
public final class DeviceRiskAdmissionConfigurationValidator implements InitializingBean {
    private final DeviceRiskAdmissionProperties properties;

    public DeviceRiskAdmissionConfigurationValidator(DeviceRiskAdmissionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.enabled()) {
            throw new IllegalStateException(DeviceRiskAdmissionProperties.PROPERTY_NAME
                    + " remains disabled until production trust, population, and runtime activation are approved");
        }
    }
}
