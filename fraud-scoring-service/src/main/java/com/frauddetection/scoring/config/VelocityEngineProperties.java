package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = VelocityEngineProperties.PREFIX)
public record VelocityEngineProperties(
        @DefaultValue("false") boolean enabled
) {
    public static final String PREFIX = "fraud.scoring.engines.velocity";
    public static final String PROPERTY_NAME = PREFIX + ".enabled";
}
