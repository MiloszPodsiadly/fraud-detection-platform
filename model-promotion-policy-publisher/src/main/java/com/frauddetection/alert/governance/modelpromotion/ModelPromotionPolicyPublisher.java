package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.GovernanceTimestampContract;
import tools.jackson.databind.ObjectMapper;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Isolated signer shared by controlled offline recovery and the durable online worker. */
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
        this.authorization = authorization;
        this.verifier = Objects.requireNonNull(verifier);
        this.store = store;
        this.clock = Objects.requireNonNull(clock);
    }

    ModelPromotionPolicyPublisher(ObjectMapper mapper, ModelPromotionPolicyValidator validator,
            ModelPromotionPolicyAuthorityVerifier verifier,
            MongoModelPromotionPolicyPublicationStore store, Clock clock) {
        this(mapper, validator, null, verifier, store, clock);
    }

    PreparedPublication prepare(Draft proposed, PrivateKey signingKey,
            String issuerIdentity, String keyId) {
        Objects.requireNonNull(authorization, "Controlled offline approval proof is required");
        Objects.requireNonNull(proposed);
        Objects.requireNonNull(signingKey);
        ModelPromotionPolicyDraft draft = Objects.requireNonNull(proposed.content());
        validator.validateDraftForPublication(draft, proposed.canonicalSha256());
        InternalPolicyApprovalArtifact proof = authorization.approvalArtifact();
        if (proof.approvalDecision().decision() != InternalPolicyApprovalArtifact.Decision.APPROVED
                || !proof.authorStatement().draftCanonicalSha256().equals(proposed.canonicalSha256())) {
            throw new SecurityException("Independent approval does not bind the exact draft");
        }
        Instant acceptedAt = ModelPromotionTrustedIssuerRegistry.canonicalTime(
                proof.approvalDecision().decidedAt(), "decidedAt");
        ModelPromotionPolicy.Content content = draft.approvedContent(acceptedAt);
        String finalDigest = validator.canonicalSha256(content);
        validator.validateUnsignedForPublication(content, finalDigest);
        InstitutionalPolicyPublicationAuthorization.Authorization approved = authorization
                .authorize(content, finalDigest)
                .orElseThrow(() -> new SecurityException("Independent policy approval or publisher mandate unavailable"));
        requireExactAuthorization(content, finalDigest, approved, issuerIdentity, keyId);
        ModelPromotionPolicy policy = sign(content, finalDigest, signingKey, issuerIdentity, keyId);
        return new PreparedPublication(policy, Instant.parse(policy.authorizationEvidence().issuedAt()),
                authorization.approvalArtifact(), approved);
    }

    PreparedOnlinePublication prepareOnline(Draft proposed, PrivateKey signingKey,
            String issuerIdentity, String keyId, Instant acceptedAt,
            OnlinePolicyApprovalVerifier.Verified verified) {
        Objects.requireNonNull(proposed);
        Objects.requireNonNull(signingKey);
        Objects.requireNonNull(verified);
        validator.validateDraftForPublication(proposed.content(), proposed.canonicalSha256());
        if (!proposed.canonicalSha256().equals(verified.artifact().draftSha256())
                || !proposed.content().equals(verified.artifact().draft())) {
            throw new SecurityException("Verified online approval does not bind the exact draft");
        }
        ModelPromotionPolicy.Content content = proposed.content().approvedContent(acceptedAt);
        String finalDigest = validator.canonicalSha256(content);
        validator.validateUnsignedForPublication(content, finalDigest);
        requireExactAuthorization(content, finalDigest, verified.authorization(), issuerIdentity, keyId);
        ModelPromotionPolicy policy = sign(content, finalDigest, signingKey, issuerIdentity, keyId);
        return new PreparedOnlinePublication(policy, verified.artifact(), verified.authorization());
    }

    private ModelPromotionPolicy sign(ModelPromotionPolicy.Content content, String finalDigest,
            PrivateKey signingKey, String issuerIdentity, String keyId) {
        Instant issued = clock.instant();
        byte[] input = ModelPromotionPolicySignatureContract.signingBytes(mapper, content, issuerIdentity,
                keyId, issued.toString());
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(input);
            ModelPromotionPolicy policy = new ModelPromotionPolicy(content, finalDigest,
                    new ModelPromotionPolicy.PolicyAuthorizationEvidence(issuerIdentity, keyId,
                            ModelPromotionPolicySignatureContract.encodeSignature(signer.sign()),
                            finalDigest, issued.toString()));
            if (verifier.verify(policy).status() != ModelPromotionPolicyAuthorityVerification.Status.VERIFIED) {
                throw new SecurityException("Signed policy was not verified by the production trust registry");
            }
            return policy;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Policy signing failed", exception);
        }
    }

    ModelPromotionPolicyPublicationReceipt publish(Draft proposed, PrivateKey signingKey,
            String issuerIdentity, String keyId) {
        return publishPrepared(prepare(proposed, signingKey, issuerIdentity, keyId));
    }

    ModelPromotionPolicyPublicationReceipt publishPrepared(PreparedPublication prepared) {
        Objects.requireNonNull(store, "Mongo publication store is required outside dry-run mode");
        return store.publish(prepared.policy(), clock.instant(), prepared.approvalArtifact(),
                prepared.authorization());
    }

    ModelPromotionPolicyPublicationReceipt publishOnlinePrepared(PreparedOnlinePublication prepared,
            MongoModelPromotionPolicyPublicationStore.PublicationLease lease) {
        Objects.requireNonNull(store, "Mongo publication store is required");
        return store.publishOnline(prepared.policy(), clock.instant(), prepared.approvalArtifact(),
                prepared.authorization(), lease);
    }

    private void requireExactAuthorization(ModelPromotionPolicy.Content content, String finalDigest,
            InstitutionalPolicyPublicationAuthorization.Authorization approved,
            String issuerIdentity, String keyId) {
        ModelPromotionPolicy.PolicyProvenance provenance = content.provenance();
        Instant from = GovernanceTimestampContract.parse(content.effectiveFromInclusive(), "effectiveFromInclusive");
        Instant to = GovernanceTimestampContract.parse(content.effectiveToExclusive(), "effectiveToExclusive");
        if (!Objects.equals(approved.policyId(), content.policyId())
                || approved.revision() != content.revision()
                || !Objects.equals(approved.canonicalSha256(), finalDigest)
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

    record Draft(ModelPromotionPolicyDraft content, String canonicalSha256) {
    }

    record PreparedPublication(ModelPromotionPolicy policy, Instant signedAt,
            InternalPolicyApprovalArtifact approvalArtifact,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) {
    }

    record PreparedOnlinePublication(ModelPromotionPolicy policy,
            OnlinePolicyApprovalArtifact approvalArtifact,
            InstitutionalPolicyPublicationAuthorization.Authorization authorization) { }
}
