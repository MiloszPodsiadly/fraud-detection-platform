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
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TrustAuthorityService {

    private static final String ALGORITHM = "Ed25519";
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private final TrustAuthorityProperties properties;
    private final KeyPair keyPair;
    private final Instant validFrom;

    public TrustAuthorityService(TrustAuthorityProperties properties) {
        this.properties = properties;
        this.keyPair = loadOrGenerateKeyPair(properties);
        this.validFrom = Instant.now();
    }

    TrustSignResponse sign(TrustSignRequest request) {
        validatePurpose(request.purpose());
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            return new TrustSignResponse(
                    Base64.getEncoder().encodeToString(signer.sign()),
                    properties.getKeyId(),
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
        if (!properties.getKeyId().equals(request.keyId())) {
            return new TrustVerifyResponse("INVALID", "UNKNOWN_KEY");
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(keyPair.getPublic());
            verifier.update(canonicalBytes(request.purpose(), request.payloadHash(), request.partitionKey(), request.chainPosition(), request.anchorId()));
            boolean valid = verifier.verify(Base64.getDecoder().decode(request.signature()));
            return valid ? new TrustVerifyResponse("VALID", null) : new TrustVerifyResponse("INVALID", "SIGNATURE_INVALID");
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return new TrustVerifyResponse("INVALID", "SIGNATURE_INVALID");
        }
    }

    TrustKeyResponse key() {
        return new TrustKeyResponse(
                properties.getKeyId(),
                ALGORITHM,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                validFrom,
                null,
                "ACTIVE"
        );
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

    private KeyPair loadOrGenerateKeyPair(TrustAuthorityProperties properties) {
        try {
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
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Trust authority key material could not be initialized.", exception);
        }
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

    private PrivateKey privateKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }

    private PublicKey publicKey(String encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded.trim())));
    }
}
