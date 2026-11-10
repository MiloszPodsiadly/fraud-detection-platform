package com.frauddetection.enricher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties("fraud.source-enriched-publications.redrive")
public record SourceEnrichedPublicationRedriveProperties(boolean enabled, String publicationId, String reason) {
    public SourceEnrichedPublicationRedriveProperties {
        if (enabled && (publicationId == null || !publicationId.matches("^[0-9a-f]{64}$") || reason == null
                || !Set.of("BROKER_RECOVERED", "OPERATOR_RECONCILIATION").contains(reason))) {
            throw new IllegalArgumentException("SOURCE_ENRICHED_REDRIVE_CONFIGURATION_INVALID");
        }
    }

    @Override public String toString() { return "SourceEnrichedPublicationRedriveProperties[redacted]"; }
}
