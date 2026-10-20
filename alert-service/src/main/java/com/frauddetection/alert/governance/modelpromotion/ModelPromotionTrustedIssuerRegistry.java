package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Startup-only trust anchors, never sourced from a policy document or MongoDB. */
final class ModelPromotionTrustedIssuerRegistry {

    static final String VERSION = "model-promotion-issuer-registry-v1";

    private final long generation;
    private final Map<String, TrustedKey> keysById;

    private ModelPromotionTrustedIssuerRegistry(long generation, Map<String, TrustedKey> keysById) {
        this.generation = generation;
        this.keysById = Map.copyOf(keysById);
    }

    static ModelPromotionTrustedIssuerRegistry load(ObjectMapper mapper, Path path, long minimumGeneration) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalStateException("Configured policy issuer registry must be a readable regular file");
            }
            Configuration configuration = mapper.readValue(Files.readString(path), Configuration.class);
            return fromConfiguration(configuration, minimumGeneration);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Configured policy issuer registry is invalid", exception);
        }
    }

    static ModelPromotionTrustedIssuerRegistry fromConfiguration(Configuration configuration, long minimumGeneration) {
        if (configuration == null || !VERSION.equals(configuration.version())
                || configuration.generation() < 1 || minimumGeneration < 1
                || configuration.generation() < minimumGeneration
                || configuration.keys() == null || configuration.keys().isEmpty()) {
            throw new IllegalStateException("Policy issuer registry version, generation or keys are invalid");
        }
        Map<String, TrustedKey> loaded = new HashMap<>();
        Set<String> publicFingerprints = new HashSet<>();
        for (ConfiguredKey configured : configuration.keys()) {
            TrustedKey key = parse(configured);
            if (loaded.putIfAbsent(key.keyId(), key) != null) {
                throw new IllegalStateException("Policy issuer key IDs must be globally unique");
            }
            if (!publicFingerprints.add(key.publicKeySha256())) {
                throw new IllegalStateException("Policy issuer public keys must be globally unique");
            }
        }
        return new ModelPromotionTrustedIssuerRegistry(configuration.generation(), loaded);
    }

    long generation() {
        return generation;
    }

    TrustedKey find(String issuerIdentity, String keyId) {
        TrustedKey key = keysById.get(keyId);
        return key != null && key.issuerIdentity().equals(issuerIdentity) ? key : null;
    }

    TrustedKey findByKeyId(String keyId) {
        return keysById.get(keyId);
    }

    private static TrustedKey parse(ConfiguredKey entry) {
        if (entry == null) {
            throw new IllegalStateException("Policy issuer key entry is required");
        }
        try {
            identifier(entry.issuerIdentity(), "issuerIdentity");
            identifier(entry.keyId(), "keyId");
            Instant from = canonicalTime(entry.validFrom(), "validFrom");
            Instant until = canonicalTime(entry.validUntil(), "validUntil");
            if (!from.isBefore(until)) {
                throw new IllegalArgumentException("Policy issuer key validity window is contradictory");
            }
            Set<String> scopes = requiredSet(entry.institutionalScopes(), "institutionalScopes");
            if (!scopes.equals(Set.of(ModelGovernanceScope.CURRENT_INSTITUTION))) {
                throw new IllegalArgumentException("Unsupported institutional scope authorization");
            }
            Set<ModelGovernanceEnvironment> environments = requiredSet(entry.environments(), "environments");
            Set<ModelPromotionIntendedUse> uses = requiredSet(entry.intendedUses(), "intendedUses");
            if (entry.status() == null) {
                throw new IllegalArgumentException("Policy issuer key status is required");
            }
            PublicKey publicKey = publicKey(entry.publicKeySpkiBase64(), entry.publicKeySha256());
            String fingerprint = entry.publicKeySha256();
            return new TrustedKey(entry.issuerIdentity(), entry.keyId(), publicKey, fingerprint,
                    scopes, environments, uses, from, until, entry.status());
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Configured policy issuer key is invalid", exception);
        }
    }

    static PublicKey publicKey(String spkiBase64, String expectedSha256) throws GeneralSecurityException {
        if (spkiBase64 == null || spkiBase64.isBlank()) {
            throw new IllegalArgumentException("Ed25519 public key material is required");
        }
        byte[] encoded = Base64.getDecoder().decode(spkiBase64);
        if (!Base64.getEncoder().encodeToString(encoded).equals(spkiBase64)) {
            throw new IllegalArgumentException("Public key must be canonical base64 DER SPKI");
        }
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        if (!java.util.Arrays.equals(encoded, key.getEncoded())) {
            throw new IllegalArgumentException("Public key must be canonical Ed25519 DER SPKI");
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        if (!fingerprint.equals(expectedSha256)) {
            throw new IllegalArgumentException("Public-key fingerprint mismatch");
        }
        return key;
    }

    static Instant canonicalTime(String value, String field) {
        Instant parsed = GovernanceTimestampContract.parse(value, field);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException(field + " must be canonical UTC");
        }
        return parsed;
    }

    static <T> Set<T> requiredSet(List<T> values, String field) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(field + " must be explicitly nonempty");
        }
        Set<T> result = new HashSet<>(values);
        if (result.size() != values.size()) {
            throw new IllegalArgumentException(field + " contains duplicates");
        }
        return Set.copyOf(result);
    }

    static void identifier(String value, String field) {
        if (value == null || !value.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    record Configuration(String version, long generation, List<ConfiguredKey> keys) {
    }

    record ConfiguredKey(
            String issuerIdentity,
            String keyId,
            String publicKeySpkiBase64,
            String publicKeySha256,
            List<String> institutionalScopes,
            List<ModelGovernanceEnvironment> environments,
            List<ModelPromotionIntendedUse> intendedUses,
            String validFrom,
            String validUntil,
            KeyStatus status
    ) {
    }

    record TrustedKey(
            String issuerIdentity,
            String keyId,
            PublicKey publicKey,
            String publicKeySha256,
            Set<String> institutionalScopes,
            Set<ModelGovernanceEnvironment> environments,
            Set<ModelPromotionIntendedUse> intendedUses,
            Instant validFrom,
            Instant validUntil,
            KeyStatus status
    ) {
    }

    enum KeyStatus {
        ACTIVE,
        RETIRED,
        REVOKED
    }
}
