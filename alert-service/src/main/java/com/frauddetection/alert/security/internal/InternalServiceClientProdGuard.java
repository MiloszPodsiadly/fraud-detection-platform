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
        if ("DISABLED_LOCAL_ONLY".equals(properties.normalizedMode())) {
            throw new IllegalStateException("DISABLED_LOCAL_ONLY internal auth client mode is forbidden in prod-like profiles.");
        }
        if ("TOKEN_VALIDATOR".equals(properties.normalizedMode())) {
            if (!properties.allowTokenValidatorInProd()) {
                throw new IllegalStateException("TOKEN_VALIDATOR internal auth client mode requires explicit prod compatibility opt-in.");
            }
            if (properties.normalizedToken().isBlank()) {
                throw new IllegalStateException("Internal service auth client token is required in prod-like profiles.");
            }
        }
        if ("JWT_SERVICE_IDENTITY".equals(properties.normalizedMode()) && !properties.jwt().complete()) {
            throw new IllegalStateException("JWT_SERVICE_IDENTITY internal auth client mode requires issuer, audience, HS256 secret of at least 32 bytes, ttl, and authorities in prod-like profiles.");
        }
        if ("MTLS_READY".equals(properties.normalizedMode())) {
            throw new IllegalStateException("MTLS_READY internal auth client mode is a fail-closed placeholder until mTLS is configured.");
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
