package com.frauddetection.alert.security.internal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class InternalServiceClientProdGuard implements InitializingBean {

    private static final Set<String> PROD_LIKE_PROFILES = Set.of("prod", "production", "staging");

    private final InternalServiceClientProperties properties;
    private final Environment environment;

    InternalServiceClientProdGuard(InternalServiceClientProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!prodLikeProfileActive()) {
            return;
        }
        if (!properties.enabled()) {
            throw new IllegalStateException("Internal service auth client must be enabled in prod-like profiles.");
        }
        if (properties.normalizedServiceName().isBlank()) {
            throw new IllegalStateException("Internal service auth client service name is required in prod-like profiles.");
        }
        if (properties.normalizedToken().isBlank()) {
            throw new IllegalStateException("Internal service auth client token is required in prod-like profiles.");
        }
    }

    private boolean prodLikeProfileActive() {
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        return activeProfiles.stream().anyMatch(PROD_LIKE_PROFILES::contains);
    }
}
