package com.frauddetection.trustauthority;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Component
public class TrustAuthorityRuntimeGuard implements ApplicationRunner {

    private final TrustAuthorityProperties properties;
    private final Environment environment;

    public TrustAuthorityRuntimeGuard(TrustAuthorityProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!prodLikeProfile()) {
            return;
        }
        if (properties.isEnabled() && !properties.isSigningRequired()) {
            throw new IllegalStateException("Prod-like trust authority requires signing-required=true when enabled.");
        }
        if (!StringUtils.hasText(properties.getInternalToken())
                || TrustAuthorityProperties.DEFAULT_LOCAL_TOKEN.equals(properties.getInternalToken())) {
            throw new IllegalStateException("Prod-like trust authority requires an explicit non-default internal token.");
        }
        if (properties.getCallers().isEmpty()) {
            throw new IllegalStateException("Prod-like trust authority requires an explicit caller allowlist.");
        }
        properties.getCallers().forEach(caller -> {
            if (!StringUtils.hasText(caller.getServiceName())) {
                throw new IllegalStateException("Prod-like trust authority caller allowlist requires service names.");
            }
            if (!StringUtils.hasText(caller.getInternalToken())
                    || TrustAuthorityProperties.DEFAULT_LOCAL_TOKEN.equals(caller.getInternalToken())) {
                throw new IllegalStateException("Prod-like trust authority caller allowlist requires per-caller non-default tokens.");
            }
        });
        if (properties.getKeys().isEmpty()) {
            requirePersistentPath(properties.getPrivateKeyPath(), "private key");
            requirePersistentPath(properties.getPublicKeyPath(), "public key");
            return;
        }
        properties.getKeys().forEach(key -> {
            if (!"REVOKED".equalsIgnoreCase(key.getStatus())) {
                requirePersistentPath(key.getPublicKeyPath(), "public key");
            }
            if ("ACTIVE".equalsIgnoreCase(key.getStatus())) {
                requirePersistentPath(key.getPrivateKeyPath(), "private key");
            }
        });
    }

    private boolean prodLikeProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::toLowerCase)
                .anyMatch(profile -> profile.equals("prod")
                        || profile.equals("production")
                        || profile.equals("staging"));
    }

    private void requirePersistentPath(String path, String material) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException("Prod-like trust authority requires explicit persistent " + material + " path.");
        }
    }
}
