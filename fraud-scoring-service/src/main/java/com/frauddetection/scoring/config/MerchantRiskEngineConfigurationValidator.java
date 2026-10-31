package com.frauddetection.scoring.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/** Always rejects live activation until independent admission and downstream contract gates have been implemented. */
public final class MerchantRiskEngineConfigurationValidator implements InitializingBean {
    private final MerchantRiskEngineProperties properties;

    public MerchantRiskEngineConfigurationValidator(MerchantRiskEngineProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.enabled()) {
            throw new IllegalStateException(MerchantRiskEngineProperties.PROPERTY_NAME
                    + " cannot be enabled: independent merchant occurrence admission and downstream contract approval are unavailable");
        }
    }
}
