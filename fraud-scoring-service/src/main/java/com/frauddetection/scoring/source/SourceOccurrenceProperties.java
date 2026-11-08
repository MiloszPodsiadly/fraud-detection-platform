package com.frauddetection.scoring.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** No default key, issuer or production retention decision. Enabling explicitly opts into durable source publication. */
@ConfigurationProperties("fraud.source-occurrences")
public record SourceOccurrenceProperties(boolean enabled, String activeKeyId, Map<String, String> encryptionKeys,
                                          Duration payloadRetention) {
    public SourceOccurrenceProperties {
        encryptionKeys = encryptionKeys == null ? Map.of() : Map.copyOf(encryptionKeys);
        if (enabled && (payloadRetention == null || payloadRetention.isNegative() || payloadRetention.isZero()
                || payloadRetention.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException("SOURCE_OCCURRENCE_RETENTION_REQUIRED");
        }
    }
    @Override public String toString() { return "SourceOccurrenceProperties[redacted]"; }
}
