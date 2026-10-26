package com.frauddetection.enricher.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/** The flag cannot activate admission until a real issuer and scoped authorization are implemented. */
public final class DeviceRiskAdmissionConfigurationValidator implements InitializingBean {
    private final DeviceRiskAdmissionProperties properties;

    public DeviceRiskAdmissionConfigurationValidator(DeviceRiskAdmissionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.enabled()) {
            throw new IllegalStateException(DeviceRiskAdmissionProperties.PROPERTY_NAME
                    + " is unsupported until an authenticated issuer, scoped authorization, and signed coverage "
                    + "seal are implemented");
        }
    }
}
