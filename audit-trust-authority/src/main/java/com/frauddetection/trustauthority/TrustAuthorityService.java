package com.frauddetection.trustauthority;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TrustAuthorityService {

    private static final String ALGORITHM = "Ed25519";
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final TrustAuthorityProperties properties;
    private final List<RegisteredKey> keys;
    private final RegisteredKey activeKey;

    public TrustAuthorityService(TrustAuthorityProperties properties) {
        this.properties = properties;
        this.keys = loadKeys(properties);
        this.activeKey = keys.stream()
                .filter(key -> "ACTIVE".equals(key.status()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Trust authority requires one active signing key."));
        if (activeKey.privateKey() == null) {
            throw new IllegalStateException("Active trust authority key requires private key material.");
        }
    }

    TrustSignResponse sign(TrustSignRequest request) {
        validatePurpose(request.purpose());
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(activeKey.privateKey());
            signer.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            return new TrustSignResponse(
                    Base64.getEncoder().encodeToString(signer.sign()),
                    activeKey.keyId(),
                    ALGORITHM,
                    Instant.now(),
                    properties.getAuthorityName()
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority signing failed.");
        }
    }

    TrustVerifyResponse verify(TrustVerifyRequest request) {
        validatePurpose(request.purpose());
        RegisteredKey key = keys.stream()
                .filter(candidate -> candidate.keyId().equals(request.keyId()))
                .findFirst()
                .orElse(null);
        if (key == null) {
            return new TrustVerifyResponse("INVALID", "UNKNOWN_KEY");
        }
        if ("REVOKED".equals(key.status())) {
            return new TrustVerifyResponse("INVALID", "KEY_REVOKED");
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key.publicKey());
            verifier.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            boolean valid = verifier.verify(Base64.getDecoder().decode(request.signature()));
            return valid ? new TrustVerifyResponse("VALID", null) : new TrustVerifyResponse("INVALID", "SIGNATURE_INVALID");
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return new TrustVerifyResponse("INVALID", "SIGNATURE_INVALID");
        }
    }

    List<TrustKeyResponse> keys() {
        return keys.stream()
                .sorted(Comparator.comparing(RegisteredKey::keyId))
                .map(key -> new TrustKeyResponse(
                        key.keyId(),
                        ALGORITHM,
                        Base64.getEncoder().encodeToString(key.publicKey().getEncoded()),
                        key.validFrom(),
                        key.validUntil(),
                        key.status()
                ))
                .toList();
    }

    TrustKeyResponse key() {
        return keys().stream()
                .filter(key -> activeKey.keyId().equals(key.keyId()))
                .findFirst()
                .orElseThrow();
    }

    private List<RegisteredKey> loadKeys(TrustAuthorityProperties properties) {
        if (!properties.getKeys().isEmpty()) {
            return properties.getKeys().stream()
                    .map(this::loadConfiguredKey)
                    .toList();
        }
        return List.of(loadLegacyKey(properties));
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
                    entry.getValidFrom() == null ? Instant.now() : entry.getValidFrom(),
                    entry.getValidUntil()
            );
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority configured key material could not be initialized.", exception);
        }
    }

    private RegisteredKey loadLegacyKey(TrustAuthorityProperties properties) {
        try {
            KeyPair keyPair = loadOrGenerateKeyPair(properties);
            return new RegisteredKey(
                    properties.getKeyId(),
                    keyPair.getPublic(),
                    keyPair.getPrivate(),
                    "ACTIVE",
                    Instant.now(),
                    null
            );
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority key material could not be initialized.", exception);
        }
    }

    private byte[] canonicalBytes(String purpose, String payloadHash, String partitionKey, long chainPosition, String anchorId) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("anchor_id", anchorId);
        canonical.put("chain_position", chainPosition);
        canonical.put("partition_key", partitionKey);
        canonical.put("payload_hash", payloadHash);
        canonical.put("purpose", purpose);
        try {
            return CANONICAL_JSON.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Trust authority canonical payload could not be serialized.");
        }
    }

    private void validatePurpose(String purpose) {
        if (!"AUDIT_ANCHOR".equals(purpose)) {
            throw new IllegalArgumentException("Unsupported trust signing purpose.");
        }
    }

    private KeyPair loadOrGenerateKeyPair(TrustAuthorityProperties properties) throws IOException, GeneralSecurityException {
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
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private PublicKey publicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase() : "ACTIVE";
        if (!List.of("ACTIVE", "RETIRED", "REVOKED").contains(normalized)) {
            throw new IllegalStateException("Unsupported trust authority key status.");
        }
        return normalized;
    }

    private record RegisteredKey(
            String keyId,
            PublicKey publicKey,
            PrivateKey privateKey,
            String status,
            Instant validFrom,
            Instant validUntil
    ) {
    }
}
