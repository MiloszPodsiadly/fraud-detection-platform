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
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
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
        List<RegisteredKey> activeKeys = keys.stream()
                .filter(key -> "ACTIVE".equals(key.status()))
                .toList();
        if (activeKeys.size() != 1) {
            throw new IllegalStateException("Trust authority requires exactly one active signing key.");
        }
        this.activeKey = activeKeys.getFirst();
        if (activeKey.privateKey() == null) {
            throw new IllegalStateException("Active trust authority key requires private key material.");
        }
        verifyKeyPair(activeKey);
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
        if (properties.getKeys().isEmpty()) {
            throw new IllegalStateException("Trust authority requires an explicit signing key registry.");
        }
        List<RegisteredKey> loaded = properties.getKeys().stream()
                .map(entry -> loadConfiguredKey(entry, localKeyGenerationAllowed))
                .toList();
        HashSet<String> keyIds = new HashSet<>();
        for (RegisteredKey key : loaded) {
            if (!StringUtils.hasText(key.keyId())
                    || !key.keyId().equals(key.keyId().trim())
                    || !keyIds.add(key.keyId())) {
                throw new IllegalStateException("Trust authority key ids must be nonblank and globally unique.");
            }
            if (key.validUntil() != null && key.validUntil().isBefore(key.validFrom())) {
                throw new IllegalStateException("Trust authority key validity window is contradictory.");
            }
        }
        return loaded;
    }

    private RegisteredKey loadConfiguredKey(
            TrustAuthorityProperties.KeyEntry entry,
            boolean localKeyGenerationAllowed
    ) {
        if (!ALGORITHM.equals(entry.getAlgorithm())) {
            throw new IllegalStateException("Unsupported trust authority key algorithm.");
        }
        try {
            if (requiresLocalGeneration(entry)) {
                if (!localKeyGenerationAllowed || !"ACTIVE".equals(normalizeStatus(entry.getStatus()))) {
                    throw new IllegalStateException("Trust authority key material is required outside local/dev/test profiles.");
                }
                KeyPair generated = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
                writeKeysIfConfigured(entry, generated);
                return registeredKey(entry, generated.getPublic(), generated.getPrivate());
            }
            PublicKey publicKey = publicKey(readKeyMaterial(entry.getPublicKey(), entry.getPublicKeyPath()));
            PrivateKey privateKey = StringUtils.hasText(entry.getPrivateKey()) || StringUtils.hasText(entry.getPrivateKeyPath())
                    ? privateKey(readKeyMaterial(entry.getPrivateKey(), entry.getPrivateKeyPath()))
                    : null;
            return registeredKey(entry, publicKey, privateKey);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority configured key material could not be initialized.", exception);
        }
    }

    private RegisteredKey registeredKey(
            TrustAuthorityProperties.KeyEntry entry,
            PublicKey publicKey,
            PrivateKey privateKey
    ) {
        return new RegisteredKey(
                entry.getKeyId(),
                publicKey,
                privateKey,
                normalizeStatus(entry.getStatus()),
                entry.getValidFrom() == null ? Instant.EPOCH : entry.getValidFrom(),
                entry.getValidUntil()
        );
    }

    private boolean requiresLocalGeneration(TrustAuthorityProperties.KeyEntry entry) {
        boolean inlineMaterial = StringUtils.hasText(entry.getPublicKey()) || StringUtils.hasText(entry.getPrivateKey());
        boolean persistedMaterial = StringUtils.hasText(entry.getPublicKeyPath())
                && StringUtils.hasText(entry.getPrivateKeyPath())
                && Files.exists(Path.of(entry.getPublicKeyPath()))
                && Files.exists(Path.of(entry.getPrivateKeyPath()));
        return !inlineMaterial && !persistedMaterial;
    }

    private void writeKeysIfConfigured(TrustAuthorityProperties.KeyEntry entry, KeyPair generated) throws IOException {
        if (StringUtils.hasText(entry.getPrivateKeyPath())) {
            Path path = Path.of(entry.getPrivateKeyPath());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()));
        }
        if (StringUtils.hasText(entry.getPublicKeyPath())) {
            Path path = Path.of(entry.getPublicKeyPath());
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
        if (!StringUtils.hasText(status)) {
            throw new IllegalStateException("Unsupported trust authority key status.");
        }
        String normalized = status.trim().toUpperCase();
        if (!List.of("ACTIVE", "RETIRED", "REVOKED").contains(normalized)) {
            throw new IllegalStateException("Unsupported trust authority key status.");
        }
        return normalized;
    }

    private void verifyKeyPair(RegisteredKey key) {
        try {
            byte[] challenge = "trust-authority-key-pair-validation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key.privateKey());
            signer.update(challenge);
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(challenge);
            if (!verifier.verify(signer.sign())) {
                throw new IllegalStateException("Active trust authority public and private keys do not match.");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Active trust authority key pair could not be validated.", exception);
        }
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
