package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** An attempted live activation is rejected; this property never creates an engine. */
@ConfigurationProperties(prefix = MerchantRiskEngineProperties.PREFIX)
public record MerchantRiskEngineProperties(@DefaultValue("false") boolean enabled) {
    public static final String PREFIX = "fraud.scoring.engines.merchant";
    public static final String PROPERTY_NAME = PREFIX + ".enabled";
}
