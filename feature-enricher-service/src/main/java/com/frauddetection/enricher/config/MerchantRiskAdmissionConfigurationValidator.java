package com.frauddetection.enricher.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/** No enablement path exists until independent merchant authority and the remaining external gates are implemented. */
public final class MerchantRiskAdmissionConfigurationValidator implements InitializingBean {
    private final MerchantRiskAdmissionProperties properties;

    public MerchantRiskAdmissionConfigurationValidator(MerchantRiskAdmissionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties is required");
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.enabled()) {
            throw new IllegalStateException(MerchantRiskAdmissionProperties.PROPERTY_NAME
                    + " cannot be enabled: independent issuer verification, scoped occurrence binding, coverage "
                    + "and approved retention are unavailable");
        }
    }
}
