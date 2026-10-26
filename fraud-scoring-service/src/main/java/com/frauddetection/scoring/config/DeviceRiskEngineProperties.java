package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = DeviceRiskEngineProperties.PREFIX)
public record DeviceRiskEngineProperties(@DefaultValue("false") boolean enabled) {
    public static final String PREFIX = "fraud.scoring.engines.device";
    public static final String PROPERTY_NAME = PREFIX + ".enabled";
}
