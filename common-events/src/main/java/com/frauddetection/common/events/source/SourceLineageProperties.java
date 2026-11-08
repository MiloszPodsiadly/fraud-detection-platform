package com.frauddetection.common.events.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fraud.source-lineage")
public record SourceLineageProperties(boolean enabled, String mongoUri, String database, String expectedMongoUser) {
    public SourceLineageProperties {
        if (enabled) {
            if (database == null || !database.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("SOURCE_LINEAGE_DATABASE_REQUIRED");
            }
            SourceScope.requireReference(expectedMongoUser);
        }
    }
    @Override public String toString() { return "SourceLineageProperties[redacted]"; }
}
