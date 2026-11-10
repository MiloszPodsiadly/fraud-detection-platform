package com.frauddetection.enricher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fraud.source-enriched-publications.recovery")
public record SourceEnrichedPublicationRecoveryProperties(@DefaultValue("false") boolean enabled,
                                                          @DefaultValue("10") int batchSize,
                                                          @DefaultValue("5000") long initialDelayMillis,
                                                          @DefaultValue("30000") long fixedDelayMillis) {
    public SourceEnrichedPublicationRecoveryProperties {
        if (batchSize < 1 || batchSize > 50 || initialDelayMillis < 0
                || fixedDelayMillis < 1_000 || fixedDelayMillis > 300_000) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_RECOVERY_CONFIGURATION_INVALID");
        }
    }

    @Override public String toString() { return "SourceEnrichedPublicationRecoveryProperties[redacted]"; }
}
