package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.artifact.GovernanceCanonicalJson;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deployment-owned public mandates, shared by publication and read-side verification. */
final class InternalPolicyApprovalTrustRegistry {

    static final String VERSION = "model-promotion-approval-trust-v1";

    private final long generation;
    private final String canonicalSha256;
    private final Map<String, TrustedKey> keysById;

    private InternalPolicyApprovalTrustRegistry(long generation, String canonicalSha256,
            Map<String, TrustedKey> keysById) {
        this.generation = generation;
        this.canonicalSha256 = canonicalSha256;
        this.keysById = Map.copyOf(keysById);
    }

    static InternalPolicyApprovalTrustRegistry load(ObjectMapper mapper, Path path, long minimumGeneration) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalStateException("Approval trust registry must be a readable regular file");
            }
            return fromConfiguration(mapper.readValue(Files.readString(path), Configuration.class), minimumGeneration);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Approval trust registry is invalid", exception);
        }
    }

    static InternalPolicyApprovalTrustRegistry fromConfiguration(Configuration configuration, long minimumGeneration) {
        if (configuration == null || !VERSION.equals(configuration.version())
                || configuration.generation() < 1 || minimumGeneration < 1
                || configuration.generation() < minimumGeneration
                || configuration.keys() == null || configuration.keys().isEmpty()) {
            throw new IllegalStateException("Approval trust registry version, generation or keys are invalid");
        }
        Map<String, TrustedKey> loaded = new HashMap<>();
        Set<String> publicFingerprints = new HashSet<>();
        for (ConfiguredKey configured : configuration.keys()) {
            TrustedKey key = parse(configured);
            if (loaded.putIfAbsent(key.keyId(), key) != null) {
                throw new IllegalStateException("Approval trust key IDs must be globally unique");
            }
            if (!publicFingerprints.add(key.publicKeySha256())) {
                throw new IllegalStateException("Approval trust public keys must be globally unique");
            }
        }
        return new InternalPolicyApprovalTrustRegistry(configuration.generation(),
                GovernanceCanonicalJson.sha256(new ObjectMapper(), configuration), loaded);
    }

    long generation() {
        return generation;
    }

    List<TrustedKey> keys() {
        return keysById.values().stream().sorted(java.util.Comparator.comparing(TrustedKey::keyId)).toList();
    }

    String canonicalSha256() {
        return canonicalSha256;
    }

    TrustedKey find(String identity, String keyId) {
        TrustedKey key = keysById.get(keyId);
        return key != null && key.identity().equals(identity) ? key : null;
    }

    private static TrustedKey parse(ConfiguredKey entry) {
        if (entry == null) throw new IllegalStateException("Approval trust key entry is required");
        try {
            ModelPromotionTrustedIssuerRegistry.identifier(entry.identity(), "identity");
            ModelPromotionTrustedIssuerRegistry.identifier(entry.keyId(), "keyId");
            PublicKey publicKey = ModelPromotionTrustedIssuerRegistry.publicKey(
                    entry.publicKeySpkiBase64(), entry.publicKeySha256());
            Set<Role> roles = ModelPromotionTrustedIssuerRegistry.requiredSet(entry.roles(), "roles");
            Set<String> scopes = ModelPromotionTrustedIssuerRegistry.requiredSet(
                    entry.institutionalScopes(), "institutionalScopes");
            if (!scopes.equals(Set.of(ModelGovernanceScope.CURRENT_INSTITUTION))) {
                throw new IllegalArgumentException("Unsupported approval institutional scope");
            }
            Set<ModelGovernanceEnvironment> environments = ModelPromotionTrustedIssuerRegistry.requiredSet(
                    entry.environments(), "environments");
            Set<ModelPromotionIntendedUse> uses = ModelPromotionTrustedIssuerRegistry.requiredSet(
                    entry.intendedUses(), "intendedUses");
            Instant from = ModelPromotionTrustedIssuerRegistry.canonicalTime(entry.validFrom(), "validFrom");
            Instant until = ModelPromotionTrustedIssuerRegistry.canonicalTime(entry.validUntil(), "validUntil");
            if (!from.isBefore(until) || entry.status() == null) {
                throw new IllegalArgumentException("Approval trust key validity or status is invalid");
            }
            return new TrustedKey(entry.identity(), entry.keyId(), publicKey, entry.publicKeySha256(), roles,
                    scopes, environments, uses, from, until, entry.status());
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Approval trust key is invalid", exception);
        }
    }

    record Configuration(String version, long generation, List<ConfiguredKey> keys) {
    }

    record ConfiguredKey(
            String identity,
            String keyId,
            String publicKeySpkiBase64,
            String publicKeySha256,
            List<Role> roles,
            List<String> institutionalScopes,
            List<ModelGovernanceEnvironment> environments,
            List<ModelPromotionIntendedUse> intendedUses,
            String validFrom,
            String validUntil,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status
    ) {
    }

    record TrustedKey(
            String identity,
            String keyId,
            PublicKey publicKey,
            String publicKeySha256,
            Set<Role> roles,
            Set<String> institutionalScopes,
            Set<ModelGovernanceEnvironment> environments,
            Set<ModelPromotionIntendedUse> intendedUses,
            Instant validFrom,
            Instant validUntil,
            ModelPromotionTrustedIssuerRegistry.KeyStatus status
    ) {
    }

    enum Role {
        POLICY_AUTHOR,
        POLICY_APPROVER,
        POLICY_PUBLISHER,
        POLICY_ISSUER
    }
}
