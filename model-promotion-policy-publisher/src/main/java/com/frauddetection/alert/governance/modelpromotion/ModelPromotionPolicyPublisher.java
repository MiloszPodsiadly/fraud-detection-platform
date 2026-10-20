package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import tools.jackson.databind.ObjectMapper;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Offline-only orchestrator; no signing key or approval implementation is in alert-service. */
final class ModelPromotionPolicyPublisher {

    private final ObjectMapper mapper;
    private final ModelPromotionPolicyValidator validator;
    private final InternalPolicyPublicationAuthorization authorization;
    private final ModelPromotionPolicyAuthorityVerifier verifier;
    private final MongoModelPromotionPolicyPublicationStore store;
    private final Clock clock;

    ModelPromotionPolicyPublisher(ObjectMapper mapper, ModelPromotionPolicyValidator validator,
            InternalPolicyPublicationAuthorization authorization,
            ModelPromotionPolicyAuthorityVerifier verifier,
            MongoModelPromotionPolicyPublicationStore store, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.validator = Objects.requireNonNull(validator);
        this.authorization = Objects.requireNonNull(authorization);
        this.verifier = Objects.requireNonNull(verifier);
        this.store = store;
        this.clock = Objects.requireNonNull(clock);
    }

    PreparedPublication prepare(UnsignedPolicy proposed, PrivateKey signingKey,
            String issuerIdentity, String keyId) {
        Objects.requireNonNull(proposed);
        Objects.requireNonNull(signingKey);
        ModelPromotionPolicy.Content content = Objects.requireNonNull(proposed.content());
        validator.validateUnsignedForPublication(content, proposed.canonicalSha256());
        InstitutionalPolicyPublicationAuthorization.Authorization approved = authorization
                .authorize(content, proposed.canonicalSha256())
                .orElseThrow(() -> new SecurityException("Independent policy approval or publisher mandate unavailable"));
        requireExactAuthorization(proposed, approved, issuerIdentity, keyId);
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        byte[] input = ModelPromotionPolicySignatureContract.signingBytes(mapper, content, issuerIdentity,
                keyId, issued.toString());
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(input);
            ModelPromotionPolicy policy = new ModelPromotionPolicy(content, proposed.canonicalSha256(),
                    new ModelPromotionPolicy.PolicyAuthorizationEvidence(issuerIdentity, keyId,
                            ModelPromotionPolicySignatureContract.encodeSignature(signer.sign()),
                            proposed.canonicalSha256(), issued.toString()));
            if (verifier.verify(policy).status() != ModelPromotionPolicyAuthorityVerification.Status.VERIFIED) {
                throw new SecurityException("Signed policy was not verified by the production trust registry");
            }
            return new PreparedPublication(policy, issued, authorization.approvalArtifact(), approved);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Policy signing failed", exception);
        }
    }

    ModelPromotionPolicyPublicationReceipt publish(UnsignedPolicy proposed, PrivateKey signingKey,
            String issuerIdentity, String keyId) {
        return publishPrepared(prepare(proposed, signingKey, issuerIdentity, keyId));
    }

    ModelPromotionPolicyPublicationReceipt publishPrepared(PreparedPublication prepared) {
        Objects.requireNonNull(store, "Mongo publication store is required outside dry-run mode");
        return store.publish(prepared.policy(), clock.instant(), prepared.approvalArtifact(),
                prepared.authorization());
    }

    private void requireExactAuthorization(UnsignedPolicy proposed,
            InstitutionalPolicyPublicationAuthorization.Authorization approved,
            String issuerIdentity, String keyId) {
        ModelPromotionPolicy.Content content = proposed.content();
        ModelPromotionPolicy.PolicyProvenance provenance = content.provenance();
        Instant from = GovernanceTimestampContract.parse(content.effectiveFromInclusive(), "effectiveFromInclusive");
        Instant to = GovernanceTimestampContract.parse(content.effectiveToExclusive(), "effectiveToExclusive");
        if (!Objects.equals(approved.policyId(), content.policyId())
                || approved.revision() != content.revision()
                || !Objects.equals(approved.canonicalSha256(), proposed.canonicalSha256())
                || !Objects.equals(approved.governanceScope(), content.governanceScope())
                || approved.intendedUse() != content.intendedUse()
                || !Objects.equals(approved.effectiveFrom(), from)
                || !Objects.equals(approved.effectiveTo(), to)
                || !Objects.equals(approved.authorIdentity(), provenance.authorIdentity())
                || !Objects.equals(approved.approverIdentity(), provenance.approvingAuthority())
                || !Objects.equals(approved.approvalReference(), provenance.approvalReference())
                || approved.publisherIdentity() == null || approved.publisherIdentity().isBlank()
                || !Objects.equals(approved.issuerIdentity(), issuerIdentity)
                || !Objects.equals(approved.keyId(), keyId)
                || approved.approvalTrustGeneration() < 1
                || !sha256(approved.approvalTrustSha256())
                || !sha256(approved.approverPublicKeySha256())
                || !sha256(approved.publisherPublicKeySha256())
                || !sha256(approved.issuerPublicKeySha256())) {
            throw new SecurityException("Independent approval does not authorize the exact policy and publisher");
        }
    }

    private boolean sha256(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    record UnsignedPolicy(ModelPromotionPolicy.Content content, String canonicalSha256) {
    }

    record PreparedPublication(ModelPromotionPolicy policy, Instant signedAt,
            InternalPolicyApprovalArtifact approvalArtifact,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) {
    }
}
