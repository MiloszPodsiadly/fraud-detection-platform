package com.frauddetection.alert.governance.modelpromotion;

import com.mongodb.MongoException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Objects;

/** Fail-closed read of the exact immutable policy and independent approval proof. */
final class MongoModelPromotionPolicyApprovalEvidenceVerifier
        implements ModelPromotionPolicyApprovalEvidenceVerifier {
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final InternalPolicyApprovalTrustRegistry approvalTrust;
    private final OnlinePolicyApprovalVerifier online;

    MongoModelPromotionPolicyApprovalEvidenceVerifier(MongoTemplate mongo, ObjectMapper mapper, Clock clock,
            InternalPolicyApprovalTrustRegistry approvalTrust, OnlinePolicyApprovalVerifier online) {
        this.mongo = Objects.requireNonNull(mongo);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.approvalTrust = Objects.requireNonNull(approvalTrust);
        this.online = online;
    }

    InternalPolicyApprovalTrustRegistry loadedApprovalTrust() { return approvalTrust; }

    OnlinePolicyActorTrustRegistry loadedActorTrust() {
        return online == null ? null : online.loadedActorTrust();
    }

    @Override
    public ModelPromotionPolicyApprovalEvidenceVerification verify(ModelPromotionPolicy policy) {
        if (policy == null || policy.content() == null) {
            return ModelPromotionPolicyApprovalEvidenceVerification.invalid(
                    ModelPromotionPolicyApprovalEvidenceVerification.Reason.EVIDENCE_INVALID);
        }
        try {
            var content = policy.content();
            String key = ModelPromotionPolicyDocument.policyKey(content.governanceScope(), content.intendedUse(),
                    content.policyId(), content.revision());
            ModelPromotionPolicyDocument persisted = mongo.findById(key, ModelPromotionPolicyDocument.class);
            InternalPolicyApprovalEvidenceDocument evidence = mongo.findById(key,
                    InternalPolicyApprovalEvidenceDocument.class);
            if (evidence == null) {
                return ModelPromotionPolicyApprovalEvidenceVerification.invalid(
                        ModelPromotionPolicyApprovalEvidenceVerification.Reason.EVIDENCE_MISSING);
            }
            if (persisted == null || !policy.equals(persisted.policy()) || persisted.publishedAt() == null
                    || !persisted.publishedAt().equals(evidence.publishedAt())
                    || !key.equals(evidence.policyKey())
                    || !policy.canonicalSha256().equals(evidence.policyCanonicalSha256())
                    || !policy.authorizationEvidence().signature().equals(evidence.policySignature())
                    || !content.governanceScope().equals(evidence.mandatedScope())
                    || content.intendedUse() != evidence.mandatedUse()
                    || !policy.authorizationEvidence().authorityIdentity().equals(evidence.issuerIdentity())
                    || !policy.authorizationEvidence().keyId().equals(evidence.issuerKeyId())
                    || evidence.publisherMandate() != InternalPolicyApprovalTrustRegistry.Role.POLICY_PUBLISHER
                    || evidence.approvalTrustGeneration() < 1
                    || !evidence.approvalTrustSha256().matches("^[0-9a-f]{64}$")) {
                return ModelPromotionPolicyApprovalEvidenceVerification.invalid(
                        ModelPromotionPolicyApprovalEvidenceVerification.Reason.EVIDENCE_INVALID);
            }
            if (evidence.onlineApprovalProof() != null) {
                if (online == null) {
                    return ModelPromotionPolicyApprovalEvidenceVerification.unavailable(
                            ModelPromotionPolicyApprovalEvidenceVerification.Reason.TRUST_UNAVAILABLE);
                }
                online.verifyPublished(policy, evidence);
            } else {
                verifyOffline(policy, evidence);
            }
            return ModelPromotionPolicyApprovalEvidenceVerification.verified();
        } catch (DataAccessException | MongoException failure) {
            return ModelPromotionPolicyApprovalEvidenceVerification.unavailable(
                    ModelPromotionPolicyApprovalEvidenceVerification.Reason.STORE_UNAVAILABLE);
        } catch (RuntimeException failure) {
            return ModelPromotionPolicyApprovalEvidenceVerification.invalid(
                    ModelPromotionPolicyApprovalEvidenceVerification.Reason.EVIDENCE_INVALID);
        }
    }

    private void verifyOffline(ModelPromotionPolicy policy, InternalPolicyApprovalEvidenceDocument evidence) {
        InternalPolicyApprovalArtifact proof = evidence.approvalProof();
        if (proof == null || !"OFFLINE_ED25519_V2".equals(evidence.proofKind())
                || !evidence.approvalProofSha256().equals(InternalPolicyApprovalArtifact.proofSha256(mapper, proof))) {
            throw new SecurityException("Offline approval proof is invalid");
        }
        var authorized = new InternalPolicyApprovalProofVerifier(mapper, approvalTrust, proof, clock)
                .authorizePublished(policy.content(), policy.canonicalSha256(), evidence.publishedAt())
                .orElseThrow(() -> new SecurityException("Independent offline approval is not currently authorized"));
        var author = proof.authorStatement();
        var decision = proof.approvalDecision();
        if (!evidence.approverIdentity().equals(authorized.approverIdentity())
                || !evidence.approverKeyId().equals(decision.approverKeyId())
                || !evidence.approverPublicKeySha256().equals(authorized.approverPublicKeySha256())
                || !evidence.publisherIdentity().equals(authorized.publisherIdentity())
                || !evidence.publisherKeyId().equals(author.publisherKeyId())
                || !evidence.publisherPublicKeySha256().equals(authorized.publisherPublicKeySha256())
                || !evidence.issuerIdentity().equals(authorized.issuerIdentity())
                || !evidence.issuerKeyId().equals(authorized.keyId())
                || !evidence.issuerPublicKeySha256().equals(authorized.issuerPublicKeySha256())
                || !authorized.canonicalSha256().equals(evidence.policyCanonicalSha256())
                || !authorized.governanceScope().equals(evidence.mandatedScope())
                || authorized.intendedUse() != evidence.mandatedUse()) {
            throw new SecurityException("Offline approval metadata is not bound to the signed policy");
        }
    }
}
