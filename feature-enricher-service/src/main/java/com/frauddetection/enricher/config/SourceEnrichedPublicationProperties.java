package com.frauddetection.enricher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties("fraud.source-enriched-publications")
public record SourceEnrichedPublicationProperties(String activeKeyId, Map<String, String> encryptionKeys) {
    public SourceEnrichedPublicationProperties {
        encryptionKeys = encryptionKeys == null ? Map.of() : Map.copyOf(encryptionKeys);
    }

    @Override public String toString() { return "SourceEnrichedPublicationProperties[redacted]"; }
}
