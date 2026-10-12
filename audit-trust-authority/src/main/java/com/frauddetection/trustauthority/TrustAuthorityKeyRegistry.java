package com.frauddetection.trustauthority;

import com.frauddetection.trustauthority.api.TrustKeyResponse;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class TrustAuthorityKeyRegistry {

    static final String ALGORITHM = "Ed25519";

    private final List<RegisteredKey> keys;
    private final RegisteredKey activeKey;

    TrustAuthorityKeyRegistry(TrustAuthorityProperties properties, Environment environment) {
        this(properties, localKeyGenerationAllowed(environment));
    }

    TrustAuthorityKeyRegistry(TrustAuthorityProperties properties, boolean localKeyGenerationAllowed) {
        this.keys = loadKeys(properties, localKeyGenerationAllowed);
        this.activeKey = keys.stream()
                .filter(key -> "ACTIVE".equals(key.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Trust authority requires one active signing key."));
        if (activeKey.privateKey() == null) {
            throw new IllegalStateException("Active trust authority key requires private key material.");
        }
    }

    RegisteredKey activeKey() {
        return activeKey;
    }

    Optional<RegisteredKey> find(String keyId) {
        return keys.stream().filter(candidate -> candidate.keyId().equals(keyId)).findFirst();
    }

    List<TrustKeyResponse> responses() {
        return keys.stream()
                .sorted(Comparator.comparing(RegisteredKey::keyId))
                .map(key -> new TrustKeyResponse(
                        key.keyId(),
                        ALGORITHM,
                        Base64.getEncoder().encodeToString(key.publicKey().getEncoded()),
                        fingerprint(key.publicKey().getEncoded()),
                        key.validFrom(),
                        key.validUntil(),
                        key.status()
                ))
                .toList();
    }

    TrustKeyResponse activeResponse() {
        return responses().stream()
                .filter(key -> activeKey.keyId().equals(key.keyId()))
                .findFirst()
                .orElseThrow();
    }

    private List<RegisteredKey> loadKeys(
            TrustAuthorityProperties properties,
            boolean localKeyGenerationAllowed
    ) {
        if (!properties.getKeys().isEmpty()) {
            return properties.getKeys().stream().map(this::loadConfiguredKey).toList();
        }
        return List.of(loadLegacyKey(properties, localKeyGenerationAllowed));
    }

    private RegisteredKey loadConfiguredKey(TrustAuthorityProperties.KeyEntry entry) {
        if (!ALGORITHM.equals(entry.getAlgorithm())) {
            throw new IllegalStateException("Unsupported trust authority key algorithm.");
        }
        try {
            PublicKey publicKey = publicKey(readKeyMaterial(entry.getPublicKey(), entry.getPublicKeyPath()));
            PrivateKey privateKey = StringUtils.hasText(entry.getPrivateKey()) || StringUtils.hasText(entry.getPrivateKeyPath())
                    ? privateKey(readKeyMaterial(entry.getPrivateKey(), entry.getPrivateKeyPath()))
                    : null;
            return new RegisteredKey(
                    entry.getKeyId(),
                    publicKey,
                    privateKey,
                    normalizeStatus(entry.getStatus()),
                    entry.getValidFrom() == null ? Instant.EPOCH : entry.getValidFrom(),
                    entry.getValidUntil()
            );
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority configured key material could not be initialized.", exception);
        }
    }

    private RegisteredKey loadLegacyKey(
            TrustAuthorityProperties properties,
            boolean localKeyGenerationAllowed
    ) {
        try {
            KeyPair keyPair = loadOrGenerateKeyPair(properties, localKeyGenerationAllowed);
            return new RegisteredKey(
                    properties.getKeyId(),
                    keyPair.getPublic(),
                    keyPair.getPrivate(),
                    "ACTIVE",
                    Instant.EPOCH,
                    null
            );
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority key material could not be initialized.", exception);
        }
    }

    private KeyPair loadOrGenerateKeyPair(
            TrustAuthorityProperties properties,
            boolean localKeyGenerationAllowed
    ) throws IOException, GeneralSecurityException {
        if (StringUtils.hasText(properties.getPrivateKeyPath()) && Files.exists(Path.of(properties.getPrivateKeyPath()))) {
            PrivateKey privateKey = privateKey(Files.readString(Path.of(properties.getPrivateKeyPath())));
            PublicKey publicKey = StringUtils.hasText(properties.getPublicKeyPath()) && Files.exists(Path.of(properties.getPublicKeyPath()))
                    ? publicKey(Files.readString(Path.of(properties.getPublicKeyPath())))
                    : null;
            if (publicKey == null) {
                throw new IllegalStateException("Trust authority public key path is required when loading a private key.");
            }
            return new KeyPair(publicKey, privateKey);
        }
        if (!localKeyGenerationAllowed) {
            throw new IllegalStateException("Trust authority key generation is allowed only for local/dev/test profiles.");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        KeyPair generated = generator.generateKeyPair();
        writeKeysIfConfigured(properties, generated);
        return generated;
    }

    private void writeKeysIfConfigured(TrustAuthorityProperties properties, KeyPair generated) throws IOException {
        if (StringUtils.hasText(properties.getPrivateKeyPath())) {
            Path path = Path.of(properties.getPrivateKeyPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()));
        }
        if (StringUtils.hasText(properties.getPublicKeyPath())) {
            Path path = Path.of(properties.getPublicKeyPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getPublic().getEncoded()));
        }
    }

    private String readKeyMaterial(String inline, String path) throws IOException {
        if (StringUtils.hasText(inline)) {
            return inline;
        }
        if (StringUtils.hasText(path)) {
            return Files.readString(Path.of(path));
        }
        throw new IllegalStateException("Configured trust authority key material is missing.");
    }

    private PrivateKey privateKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private PublicKey publicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase() : "ACTIVE";
        if (!List.of("ACTIVE", "RETIRED", "REVOKED").contains(normalized)) {
            throw new IllegalStateException("Unsupported trust authority key status.");
        }
        return normalized;
    }

    private String fingerprint(byte[] publicKey) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority key fingerprint could not be calculated.");
        }
    }

    private static boolean localKeyGenerationAllowed(Environment environment) {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        return profiles.isEmpty()
                || profiles.contains("local")
                || profiles.contains("dev")
                || profiles.contains("test")
                || profiles.contains("docker-local");
    }

    record RegisteredKey(
            String keyId,
            PublicKey publicKey,
            PrivateKey privateKey,
            String status,
            Instant validFrom,
            Instant validUntil
    ) {
    }
}
