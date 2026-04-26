package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.internal-auth.client")
public record InternalServiceClientProperties(
        boolean enabled,
        String serviceName,
        String token
) {
    public InternalServiceClientProperties {
        if (enabled) {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("app.internal-auth.client.service-name is required when internal auth is enabled");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("app.internal-auth.client.token is required when internal auth is enabled");
            }
        }
    }

    public String normalizedServiceName() {
        return serviceName == null ? "" : serviceName.trim();
    }

    public String normalizedToken() {
        return token == null ? "" : token.trim();
    }
}
