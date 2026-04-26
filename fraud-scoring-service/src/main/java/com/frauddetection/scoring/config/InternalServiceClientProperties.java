package com.frauddetection.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app.internal-auth.client")
public record InternalServiceClientProperties(
        boolean enabled,
        String mode,
        String serviceName,
        String token,
        boolean allowTokenValidatorInProd,
        Jwt jwt
) {
    private static final List<String> MODES = List.of(
            "DISABLED_LOCAL_ONLY",
            "TOKEN_VALIDATOR",
            "JWT_SERVICE_IDENTITY",
            "MTLS_READY"
    );

    public InternalServiceClientProperties {
        mode = normalizeMode(mode, enabled);
        serviceName = serviceName == null ? "" : serviceName.trim();
        token = token == null ? "" : token.trim();
        jwt = jwt == null ? Jwt.empty() : jwt.normalized();
        if (enabled && serviceName.isBlank()) {
            throw new IllegalArgumentException("app.internal-auth.client.service-name is required when internal auth is enabled");
        }
        if (enabled && "TOKEN_VALIDATOR".equals(mode) && token.isBlank()) {
            throw new IllegalArgumentException("app.internal-auth.client.token is required when TOKEN_VALIDATOR is enabled");
        }
        if (enabled && "JWT_SERVICE_IDENTITY".equals(mode) && !jwt.complete()) {
            throw new IllegalArgumentException("app.internal-auth.client.jwt issuer, audience, HS256 secret of at least 32 bytes, ttl, and authorities are required when JWT_SERVICE_IDENTITY is enabled");
        }
    }

    public String normalizedMode() {
        return mode;
    }

    public String normalizedServiceName() {
        return serviceName;
    }

    public String normalizedToken() {
        return token;
    }

    public List<String> jwtAuthorities() {
        return jwt.authorityList();
    }

    private static String normalizeMode(String mode, boolean enabled) {
        String candidate = mode == null || mode.isBlank()
                ? (enabled ? "TOKEN_VALIDATOR" : "DISABLED_LOCAL_ONLY")
                : mode.trim().toUpperCase();
        if ("REQUIRED".equals(candidate)) {
            candidate = "TOKEN_VALIDATOR";
        }
        if ("LOCALDEV".equals(candidate)) {
            candidate = "DISABLED_LOCAL_ONLY";
        }
        if (!MODES.contains(candidate)) {
            throw new IllegalArgumentException("Unsupported internal auth client mode.");
        }
        return candidate;
    }

    public record Jwt(
            String issuer,
            String audience,
            String secret,
            Duration ttl,
            String authorities
    ) {
        static Jwt empty() {
            return new Jwt("", "", "", Duration.ofMinutes(5), "");
        }

        Jwt normalized() {
            return new Jwt(
                    issuer == null ? "" : issuer.trim(),
                    audience == null ? "" : audience.trim(),
                    secret == null ? "" : secret.trim(),
                    ttl == null ? Duration.ofMinutes(5) : ttl,
                    authorities == null ? "" : authorities.trim()
            );
        }

        boolean complete() {
            return !issuer.isBlank()
                    && !audience.isBlank()
                    && !secret.isBlank()
                    && secret.getBytes(StandardCharsets.UTF_8).length >= 32
                    && ttl != null
                    && !ttl.isNegative()
                    && !ttl.isZero()
                    && !authorityList().isEmpty();
        }

        List<String> authorityList() {
            if (authorities == null || authorities.isBlank()) {
                return List.of();
            }
            return Arrays.stream(authorities.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }
    }
}
