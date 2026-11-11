package com.frauddetection.ingest.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud.source-raw-publications.redrive")
public record SourceRawPublicationRedriveProperties(boolean enabled, String receiptId, String reason) {
    public SourceRawPublicationRedriveProperties {
        if (enabled && (receiptId == null || !receiptId.matches("^[0-9a-f]{64}$") || reason == null
                || !java.util.Set.of("BROKER_RECOVERED", "OPERATOR_RECONCILIATION").contains(reason))) {
            throw new IllegalArgumentException("SOURCE_RAW_REDRIVE_CONFIGURATION_INVALID");
        }
    }

    @Override public String toString() { return "SourceRawPublicationRedriveProperties[redacted]"; }
}
