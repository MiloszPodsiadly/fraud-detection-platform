package com.frauddetection.common.events.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** Private service-to-service origin proof keys; callers never supply these values. */
@ConfigurationProperties("fraud.source-origin")
public record SourceOriginProperties(boolean enabled, String activeKeyId, Map<String, String> keys) {
    public SourceOriginProperties {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
        if (enabled) {
            SourceScope.requireReference(activeKeyId);
            if (keys.isEmpty() || !keys.containsKey(activeKeyId) || keys.size() > 8) {
                throw new IllegalArgumentException("SOURCE_ORIGIN_CONFIGURATION_INVALID");
            }
        }
    }

    @Override public String toString() { return "SourceOriginProperties[redacted]"; }
}
