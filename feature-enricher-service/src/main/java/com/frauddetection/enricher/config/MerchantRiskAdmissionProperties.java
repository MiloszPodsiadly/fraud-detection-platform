package com.frauddetection.enricher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = MerchantRiskAdmissionProperties.PREFIX)
public record MerchantRiskAdmissionProperties(@DefaultValue("false") boolean enabled) {
    public static final String PREFIX = "fraud.merchant-risk.admission";
    public static final String PROPERTY_NAME = PREFIX + ".enabled";
}
