package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = EngineIntelligenceEmissionProperties.PREFIX)
public record EngineIntelligenceEmissionProperties(
        @DefaultValue("false") boolean emitEnabled
) {
    public static final String PREFIX = "fraud.scoring.events.engine-intelligence";
    public static final String PROPERTY_NAME = PREFIX + ".emit-enabled";
}
