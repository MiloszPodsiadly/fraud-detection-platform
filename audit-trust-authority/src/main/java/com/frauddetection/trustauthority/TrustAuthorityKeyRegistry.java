package com.frauddetection.trustauthority;

import com.frauddetection.trustauthority.api.TrustKeyResponse;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
        this(properties, TrustAuthorityProfilePolicy.localKeyGenerationAllowed(environment));
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
            String status = normalizeStatus(entry.getStatus());
            KeyMaterialSource materialSource = keyMaterialSource(entry, status);
            if (materialSource.bootstrap()) {
                if (!localKeyGenerationAllowed || !"ACTIVE".equals(status)) {
                    throw new IllegalStateException("Trust authority key material is required outside local/dev/test profiles.");
                }
                KeyPair generated = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
                if (materialSource == KeyMaterialSource.FRESH_PERSISTED_BOOTSTRAP) {
                    persistFreshKeyPair(entry, generated);
                }
                return registeredKey(entry, generated.getPublic(), generated.getPrivate(), status);
            }
            PublicKey publicKey = materialSource.inline()
                    ? publicKey(entry.getPublicKey())
                    : publicKey(readPersistedKey(entry.getPublicKeyPath(), "public"));
            PrivateKey privateKey = materialSource.privateMaterial()
                    ? materialSource.inline()
                            ? privateKey(entry.getPrivateKey())
                            : privateKey(readPersistedKey(entry.getPrivateKeyPath(), "private"))
                    : null;
            return registeredKey(entry, publicKey, privateKey, status);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Trust authority configured key material could not be initialized.", exception);
        }
    }

    private RegisteredKey registeredKey(
            TrustAuthorityProperties.KeyEntry entry,
            PublicKey publicKey,
            PrivateKey privateKey,
            String status
    ) {
        return new RegisteredKey(
                entry.getKeyId(),
                publicKey,
                privateKey,
                status,
                entry.getValidFrom() == null ? Instant.EPOCH : entry.getValidFrom(),
                entry.getValidUntil()
        );
    }

    private KeyMaterialSource keyMaterialSource(TrustAuthorityProperties.KeyEntry entry, String status) {
        boolean publicInline = StringUtils.hasText(entry.getPublicKey());
        boolean privateInline = StringUtils.hasText(entry.getPrivateKey());
        boolean publicPathConfigured = StringUtils.hasText(entry.getPublicKeyPath());
        boolean privatePathConfigured = StringUtils.hasText(entry.getPrivateKeyPath());

        if (publicInline || privateInline) {
            if (publicPathConfigured || privatePathConfigured) {
                throw new IllegalStateException("Trust authority key material cannot mix inline and persisted configuration.");
            }
            if (!publicInline || ("ACTIVE".equals(status) && !privateInline)) {
                throw new IllegalStateException("Trust authority inline key material requires a complete key pair.");
            }
            return privateInline ? KeyMaterialSource.INLINE_PAIR : KeyMaterialSource.INLINE_PUBLIC;
        }
        if (publicPathConfigured || privatePathConfigured) {
            if (!publicPathConfigured || ("ACTIVE".equals(status) && !privatePathConfigured)) {
                throw new IllegalStateException("Trust authority persisted key material requires both key paths.");
            }
            Path publicPath = Path.of(entry.getPublicKeyPath()).toAbsolutePath().normalize();
            Path privatePath = privatePathConfigured
                    ? Path.of(entry.getPrivateKeyPath()).toAbsolutePath().normalize()
                    : null;
            if (privatePath != null && publicPath.equals(privatePath)) {
                throw new IllegalStateException("Trust authority public and private key paths must be distinct.");
            }
            boolean publicExists = Files.exists(publicPath);
            boolean privateExists = privatePath != null && Files.exists(privatePath);
            if (!publicExists && privateExists) {
                throw new IllegalStateException("Trust authority persisted key material is partial; bootstrap will not overwrite it.");
            }
            if ("ACTIVE".equals(status)) {
                if (publicExists != privateExists) {
                    throw new IllegalStateException("Trust authority persisted key material is partial; bootstrap will not overwrite it.");
                }
                return publicExists ? KeyMaterialSource.PERSISTED_PAIR : KeyMaterialSource.FRESH_PERSISTED_BOOTSTRAP;
            }
            if (!publicExists) {
                throw new IllegalStateException("Trust authority verification key requires persisted public key material.");
            }
            return privateExists ? KeyMaterialSource.PERSISTED_PAIR : KeyMaterialSource.PERSISTED_PUBLIC;
        }
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("Trust authority verification key requires public key material.");
        }
        return KeyMaterialSource.EPHEMERAL_BOOTSTRAP;
    }

    private void persistFreshKeyPair(TrustAuthorityProperties.KeyEntry entry, KeyPair generated) throws IOException {
        Path privatePath = Path.of(entry.getPrivateKeyPath());
        Path publicPath = Path.of(entry.getPublicKeyPath());
        createParentDirectories(privatePath);
        createParentDirectories(publicPath);
        createPrivateKeyFile(privatePath);
        Files.writeString(
                privatePath,
                Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()),
                StandardOpenOption.WRITE
        );
        Files.writeString(
                publicPath,
                Base64.getEncoder().encodeToString(generated.getPublic().getEncoded()),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private void createPrivateKeyFile(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            )));
        } catch (UnsupportedOperationException exception) {
            Files.createFile(path);
        }
    }

    private void createParentDirectories(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
    }

    private String readPersistedKey(String path, String material) throws IOException {
        Path keyPath = Path.of(path);
        if (!Files.isRegularFile(keyPath) || !Files.isReadable(keyPath)) {
            throw new IllegalStateException("Trust authority persisted " + material + " key must be a readable file.");
        }
        return Files.readString(keyPath);
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

    record RegisteredKey(
            String keyId,
            PublicKey publicKey,
            PrivateKey privateKey,
            String status,
            Instant validFrom,
            Instant validUntil
    ) {
    }

    private enum KeyMaterialSource {
        INLINE_PAIR(false, true, true),
        INLINE_PUBLIC(false, true, false),
        PERSISTED_PAIR(false, false, true),
        PERSISTED_PUBLIC(false, false, false),
        EPHEMERAL_BOOTSTRAP(true, false, false),
        FRESH_PERSISTED_BOOTSTRAP(true, false, false);

        private final boolean bootstrap;
        private final boolean inline;
        private final boolean privateMaterial;

        KeyMaterialSource(boolean bootstrap, boolean inline, boolean privateMaterial) {
            this.bootstrap = bootstrap;
            this.inline = inline;
            this.privateMaterial = privateMaterial;
        }

        boolean bootstrap() {
            return bootstrap;
        }

        boolean inline() {
            return inline;
        }

        boolean privateMaterial() {
            return privateMaterial;
        }
    }
}
