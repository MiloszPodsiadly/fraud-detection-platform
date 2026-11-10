package com.frauddetection.enricher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fraud.source-enriched-publications.recovery")
public record SourceEnrichedPublicationRecoveryProperties(@DefaultValue("false") boolean enabled,
                                                          @DefaultValue("10") int batchSize,
                                                          @DefaultValue("5000") long initialDelayMillis,
                                                          @DefaultValue("30000") long fixedDelayMillis,
                                                          @DefaultValue("5000") long kafkaAckTimeoutMillis) {
    public static final long PUBLICATION_LEASE_MILLIS = 30_000;
    public static final long PROCESSING_OVERHEAD_RESERVE_MILLIS = 20_000;

    public SourceEnrichedPublicationRecoveryProperties(boolean enabled, int batchSize, long initialDelayMillis, long fixedDelayMillis) {
        this(enabled, batchSize, initialDelayMillis, fixedDelayMillis, 5_000);
    }

    @ConstructorBinding
    public SourceEnrichedPublicationRecoveryProperties {
        if (batchSize < 1 || batchSize > 50 || initialDelayMillis < 0
                || fixedDelayMillis < 1_000 || fixedDelayMillis > 300_000
                || kafkaAckTimeoutMillis < 1_000
                || kafkaAckTimeoutMillis > PUBLICATION_LEASE_MILLIS - PROCESSING_OVERHEAD_RESERVE_MILLIS) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_RECOVERY_CONFIGURATION_INVALID");
        }
    }

    @Override public String toString() { return "SourceEnrichedPublicationRecoveryProperties[redacted]"; }
}
