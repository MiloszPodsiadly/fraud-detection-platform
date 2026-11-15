package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.source.SourceSignatureVerifier;
import com.frauddetection.common.events.source.SourceVerificationProperties;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;

/** Explicit server-owned role grants; key identity and authority are both required. */
final class MerchantAssertionTrustPolicy {
    static final String AUDIENCE = "feature-enricher-merchant-private";
    private static final int MAX_GRANTS = 64;
    private final Map<KeyId, Grant> grants;
    private final Map<KeyId, String> keyCommitments;

    MerchantAssertionTrustPolicy(List<Grant> configuredGrants) {
        List<Grant> copy = List.copyOf(Objects.requireNonNull(configuredGrants, "MERCHANT_GRANTS_REQUIRED"));
        if (copy.isEmpty() || copy.size() > MAX_GRANTS) { throw invalidPolicy(); }
        Map<KeyId, Grant> byKey = new HashMap<>();
        Map<KeyId, String> commitments = new HashMap<>();
        Map<String, MerchantAssertionVerifier.Role> commitmentRoles = new HashMap<>();
        Map<SourceIdentity, Population> populations = new HashMap<>();
        Map<SourceIdentity, Set<MerchantAssertionVerifier.Role>> rolesByScope = new HashMap<>();
        Set<String> grantIds = new HashSet<>();
        for (Grant grant : copy) {
            Objects.requireNonNull(grant, "MERCHANT_GRANT_REQUIRED");
            KeyId id = KeyId.from(grant);
            String commitment = keyCommitment(grant.key());
            if (byKey.putIfAbsent(id, grant) != null || !grantIds.add(grant.merchantGrantId())) { throw invalidPolicy(); }
            commitments.put(id, commitment);
            MerchantAssertionVerifier.Role previousRole = commitmentRoles.putIfAbsent(commitment, grant.role());
            if (previousRole != null && previousRole != grant.role()) { throw invalidPolicy(); }
            Population previousPopulation = populations.putIfAbsent(grant.sourceIdentity(), grant.population());
            if (previousPopulation != null && !previousPopulation.equals(grant.population())) { throw invalidPolicy(); }
            rolesByScope.computeIfAbsent(grant.sourceIdentity(), ignored -> new HashSet<>()).add(grant.role());
        }
        if (rolesByScope.values().stream().anyMatch(roles -> !roles.containsAll(Set.of(
                MerchantAssertionVerifier.Role.OBSERVATION, MerchantAssertionVerifier.Role.COVERAGE)))) {
            throw invalidPolicy();
        }
        this.grants = Map.copyOf(byKey);
        this.keyCommitments = Map.copyOf(commitments);
    }

    List<SourceVerificationProperties.TrustedKey> keys() {
        return grants.values().stream().map(Grant::key).toList();
    }

    Grant grant(MerchantAssertionVerifier.Role role, SourceSignatureVerifier.Verified evidence) {
        if (evidence == null) { return null; }
        Grant grant = grants.get(KeyId.from(role, evidence.scope().issuer(), evidence.scope().sourceEpoch(),
                evidence.scope().signingKeyVersion()));
        String keyCommitment = grant == null ? null : keyCommitments.get(KeyId.from(grant));
        if (grant == null || grant.role() != role || !Objects.equals(keyCommitment, evidence.keyCommitment())
                || !grant.key().institution().equals(evidence.scope().institution())
                || !grant.key().source().equals(evidence.scope().source())
                || !grant.key().sourceEpoch().equals(evidence.scope().sourceEpoch())
                || !role.purpose().equals(evidence.scope().purpose())
                || !AUDIENCE.equals(evidence.scope().audience())
                || !grant.key().policyVersion().equals(evidence.policyVersion())) { return null; }
        return grant;
    }

    boolean stillAuthorized(Grant original, MerchantAssertionVerifier.Role role, SourceSignatureVerifier.Verified evidence) {
        Grant current = grant(role, evidence);
        return original != null && original.equals(current);
    }

    static String keyCommitment(SourceVerificationProperties.TrustedKey key) {
        try {
            byte[] encoded = Base64.getDecoder().decode(key.publicKeyBase64());
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        } catch (Exception invalid) {
            throw invalidPolicy();
        }
    }

    private static IllegalArgumentException invalidPolicy() {
        return new IllegalArgumentException("MERCHANT_ASSERTION_POLICY_INVALID");
    }

    private record KeyId(MerchantAssertionVerifier.Role role, String issuer, String sourceEpoch, String keyVersion) {
        private static KeyId from(Grant grant) {
            return from(grant.role(), grant.key().issuer(), grant.key().sourceEpoch(), grant.key().signingKeyVersion());
        }
        private static KeyId from(MerchantAssertionVerifier.Role role, String issuer, String sourceEpoch, String keyVersion) {
            return new KeyId(role, issuer, sourceEpoch, keyVersion);
        }
    }

    /** Authenticated transaction-source identity, distinct from the Merchant assertion signer identity. */
    record SourceIdentity(String issuer, String institution, String source, String sourceEpoch, int identityVersion) {
        SourceIdentity {
            MerchantAssertionCodec.reference(issuer);
            MerchantAssertionCodec.reference(institution);
            MerchantAssertionCodec.reference(source);
            MerchantAssertionCodec.reference(sourceEpoch);
            if (identityVersion < 1 || identityVersion > 65_535) { throw invalidPolicy(); }
        }
        @Override public String toString() { return "MerchantSourceIdentity[redacted]"; }
    }

    /** One population/feed contract is pinned to a source identity for its epoch. */
    record Population(String feedId, String id, int definitionVersion, String definitionCommitment,
                      String inclusionRulesCommitment, String exclusionRulesCommitment, Instant earliestCoverageStart) {
        Population {
            MerchantAssertionCodec.reference(feedId);
            MerchantAssertionCodec.reference(id);
            if (definitionVersion < 1 || definitionVersion > 65_535) { throw invalidPolicy(); }
            MerchantAssertionCodec.commitment(definitionCommitment);
            MerchantAssertionCodec.commitment(inclusionRulesCommitment);
            MerchantAssertionCodec.commitment(exclusionRulesCommitment);
            MerchantAssertionCodec.time(earliestCoverageStart);
        }
        @Override public String toString() { return "MerchantPopulationGrant[redacted]"; }
    }

    record MerchantMapping(int version, String commitment) {
        MerchantMapping {
            if (version < 1 || version > 65_535) { throw invalidPolicy(); }
            MerchantAssertionCodec.commitment(commitment);
        }
        @Override public String toString() { return "MerchantMappingGrant[redacted]"; }
    }

    record Grant(MerchantAssertionVerifier.Role role, String merchantGrantId, int merchantGrantVersion,
                 SourceVerificationProperties.TrustedKey key,
                 SourceIdentity sourceIdentity, Population population, MerchantMapping mapping) {
        Grant {
            Objects.requireNonNull(role, "MERCHANT_GRANT_ROLE_REQUIRED");
            MerchantAssertionCodec.reference(merchantGrantId);
            if (merchantGrantVersion < 1 || merchantGrantVersion > 65_535) { throw invalidPolicy(); }
            Objects.requireNonNull(key, "MERCHANT_GRANT_KEY_REQUIRED");
            Objects.requireNonNull(sourceIdentity, "MERCHANT_GRANT_SOURCE_REQUIRED");
            Objects.requireNonNull(population, "MERCHANT_GRANT_POPULATION_REQUIRED");
            if (!role.purpose().equals(key.purpose()) || !AUDIENCE.equals(key.audience())
                    || !sourceIdentity.institution().equals(key.institution())
                    || !sourceIdentity.source().equals(key.source())
                    || !sourceIdentity.sourceEpoch().equals(key.sourceEpoch())
                    || role == MerchantAssertionVerifier.Role.OBSERVATION && mapping == null
                    || role == MerchantAssertionVerifier.Role.COVERAGE && mapping != null) {
                throw invalidPolicy();
            }
        }
        @Override public String toString() { return "MerchantAssertionGrant[redacted]"; }
    }
}
