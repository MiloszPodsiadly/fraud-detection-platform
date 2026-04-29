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
        TrustAuthorityIdentityMode identityMode = properties.identityModeEnum();
        if (identityMode == TrustAuthorityIdentityMode.MTLS_READY || identityMode == TrustAuthorityIdentityMode.JWT_READY) {
            throw new IllegalStateException("Trust authority identity mode " + identityMode.configValue() + " is not implemented and fails closed.");
        }
        if (!prodLikeProfile()) {
            return;
        }
        if (properties.isEnabled() && !properties.isSigningRequired()) {
            throw new IllegalStateException("Prod-like trust authority requires signing-required=true when enabled.");
        }
        if (identityMode == null) {
            throw new IllegalStateException("Prod-like trust authority requires explicit enterprise identity mode.");
        }
        if (identityMode == TrustAuthorityIdentityMode.HMAC_LOCAL) {
            throw new IllegalStateException("Prod-like trust authority requires enterprise identity (mTLS/JWT). HMAC local mode is not permitted.");
        }
        if (identityMode == TrustAuthorityIdentityMode.MTLS_SERVICE_IDENTITY) {
            throw new IllegalStateException("Trust authority identity mode mtls-service-identity is not implemented and fails closed.");
        }
        if (identityMode == TrustAuthorityIdentityMode.JWT_SERVICE_IDENTITY) {
            validateJwtIdentity();
        }
        if (!"durable-hash-chain".equals(properties.getAudit().getSink())) {
            throw new IllegalStateException("Prod-like trust authority requires durable-hash-chain audit sink.");
        }
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

    private void validateJwtIdentity() {
        TrustAuthorityProperties.JwtIdentityProperties jwt = properties.getJwtIdentity();
        if (!StringUtils.hasText(jwt.getIssuer()) || !StringUtils.hasText(jwt.getAudience())) {
            throw new IllegalStateException("JWT_SERVICE_IDENTITY trust authority mode requires issuer and audience.");
        }
        if (jwt.getKeys().isEmpty() && !StringUtils.hasText(jwt.getJwksPath())) {
            throw new IllegalStateException("JWT_SERVICE_IDENTITY trust authority mode requires public verification keys.");
        }
        if (StringUtils.hasText(jwt.getJwksPath())) {
            Path jwksPath = Path.of(jwt.getJwksPath());
            if (!Files.exists(jwksPath) || !Files.isReadable(jwksPath)) {
                throw new IllegalStateException("JWT_SERVICE_IDENTITY JWKS path must exist and be readable.");
            }
        }
        jwt.getKeys().forEach(key -> {
            if (!StringUtils.hasText(key.getKeyId())) {
                throw new IllegalStateException("JWT_SERVICE_IDENTITY public verification keys require key ids.");
            }
            if (!StringUtils.hasText(key.getPublicKey()) && !StringUtils.hasText(key.getPublicKeyPath())) {
                throw new IllegalStateException("JWT_SERVICE_IDENTITY public verification keys require key material.");
            }
        });
        if (properties.getCallers().isEmpty()) {
            throw new IllegalStateException("JWT_SERVICE_IDENTITY trust authority mode requires an explicit caller allowlist.");
        }
        properties.getCallers().forEach(caller -> {
            if (!StringUtils.hasText(caller.getServiceName())) {
                throw new IllegalStateException("JWT_SERVICE_IDENTITY caller allowlist requires service names.");
            }
            if (caller.getAllowedPurposes().isEmpty()) {
                throw new IllegalStateException("JWT_SERVICE_IDENTITY caller allowlist requires allowed purposes.");
            }
            if (caller.getAllowedJwtKeyIds().isEmpty()) {
                throw new IllegalStateException("JWT_SERVICE_IDENTITY caller allowlist requires service-to-key binding.");
            }
        });
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
