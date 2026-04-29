package com.frauddetection.trustauthority;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

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
        String identityMode = properties.getIdentityMode() == null ? "" : properties.getIdentityMode();
        if ("hmac-local".equals(identityMode)) {
            throw new IllegalStateException("Prod-like trust authority requires enterprise identity (mTLS/JWT). HMAC local mode is not permitted.");
        }
        if ("mtls-ready".equals(identityMode) || "jwt-ready".equals(identityMode)) {
            throw new IllegalStateException("Trust authority identity mode " + identityMode + " is not implemented and fails closed.");
        }
        if (!StringUtils.hasText(identityMode)) {
            throw new IllegalStateException("Prod-like trust authority requires explicit enterprise identity mode.");
        }
        if (!Set.of("mtls-ready", "jwt-ready").contains(identityMode)) {
            throw new IllegalStateException("Prod-like trust authority identity mode is not supported.");
        }
        if (!"durable-hash-chain".equals(properties.getAudit().getSink())) {
            throw new IllegalStateException("Prod-like trust authority requires durable-hash-chain audit sink.");
        }
        if (!StringUtils.hasText(properties.getHmacSecret())
                || TrustAuthorityProperties.DEFAULT_LOCAL_HMAC_SECRET.equals(properties.getHmacSecret())) {
            throw new IllegalStateException("Prod-like trust authority requires an explicit non-default HMAC secret.");
        }
        if (properties.getCallers().isEmpty()) {
            throw new IllegalStateException("Prod-like trust authority requires an explicit caller allowlist.");
        }
        properties.getCallers().forEach(caller -> {
            if (!StringUtils.hasText(caller.getServiceName())) {
                throw new IllegalStateException("Prod-like trust authority caller allowlist requires service names.");
            }
            if (!StringUtils.hasText(caller.getHmacSecret())
                    || TrustAuthorityProperties.DEFAULT_LOCAL_HMAC_SECRET.equals(caller.getHmacSecret())) {
                throw new IllegalStateException("Prod-like trust authority caller allowlist requires per-caller non-default HMAC secrets.");
            }
        });
        if (properties.getKeys().isEmpty()) {
            requirePersistentPath(properties.getPrivateKeyPath(), "private key");
            requirePersistentPath(properties.getPublicKeyPath(), "public key");
            enforcePrivateKeyFile(properties.getPrivateKeyPath());
            return;
        }
        properties.getKeys().forEach(key -> {
            if (StringUtils.hasText(key.getPrivateKey())) {
                throw new IllegalStateException("Prod-like trust authority forbids inline private key material.");
            }
            if (!"REVOKED".equalsIgnoreCase(key.getStatus())) {
                requirePersistentPath(key.getPublicKeyPath(), "public key");
            }
            if ("ACTIVE".equalsIgnoreCase(key.getStatus())) {
                requirePersistentPath(key.getPrivateKeyPath(), "private key");
                enforcePrivateKeyFile(key.getPrivateKeyPath());
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

    private void enforcePrivateKeyFile(String path) {
        Path keyPath = Path.of(path);
        if (!Files.exists(keyPath) || !Files.isReadable(keyPath)) {
            throw new IllegalStateException("Prod-like trust authority private key must exist and be readable.");
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyPath);
            if (permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.GROUP_READ)) {
                throw new IllegalStateException("Prod-like trust authority private key must not be group/world-readable.");
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows does not expose POSIX file permissions; existence/readability is still enforced.
        } catch (IOException exception) {
            throw new IllegalStateException("Prod-like trust authority private key permissions could not be checked.", exception);
        }
    }
}
