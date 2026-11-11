package com.frauddetection.ingest.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("fraud.source-raw-publications")
public record SourceRawPublicationProperties(boolean enabled, String activeKeyId, Map<String, String> encryptionKeys,
                                              Duration pollInterval, Duration claimLease, int batchSize, int maxAttempts) {
    public SourceRawPublicationProperties {
        encryptionKeys = encryptionKeys == null ? Map.of() : Map.copyOf(encryptionKeys);
        pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
        claimLease = claimLease == null ? Duration.ofSeconds(30) : claimLease;
        batchSize = batchSize == 0 ? 10 : batchSize;
        maxAttempts = maxAttempts == 0 ? 8 : maxAttempts;
        if (enabled) {
            if (activeKeyId == null || activeKeyId.isBlank() || encryptionKeys.isEmpty()
                    || pollInterval.compareTo(Duration.ofMillis(100)) < 0 || pollInterval.compareTo(Duration.ofSeconds(60)) > 0
                    || claimLease.compareTo(Duration.ofSeconds(1)) < 0 || claimLease.compareTo(Duration.ofMinutes(5)) > 0
                    || batchSize < 1 || batchSize > 50 || maxAttempts < 1 || maxAttempts > 8) {
                throw new IllegalArgumentException("SOURCE_RAW_PUBLICATION_CONFIGURATION_INVALID");
            }
        }
    }

    @Override public String toString() { return "SourceRawPublicationProperties[redacted]"; }
}
