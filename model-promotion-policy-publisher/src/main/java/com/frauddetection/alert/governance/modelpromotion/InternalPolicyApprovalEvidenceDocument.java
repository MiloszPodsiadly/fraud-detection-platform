package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/** Audit evidence for one immutable policy revision, not a second policy authority. */
@Document(collection = InternalPolicyApprovalEvidenceDocument.COLLECTION)
@Immutable
record InternalPolicyApprovalEvidenceDocument(
        @Id String policyKey,
        String policyCanonicalSha256,
        String approvalProofSha256,
        InternalPolicyApprovalArtifact approvalProof,
        String approverIdentity,
        String approverKeyId,
        String approverPublicKeySha256,
        String publisherIdentity,
        String publisherKeyId,
        String publisherPublicKeySha256,
        long approvalTrustGeneration,
        String approvalTrustSha256,
        InternalPolicyApprovalTrustRegistry.Role publisherMandate,
        ModelGovernanceScope mandatedScope,
        ModelPromotionIntendedUse mandatedUse,
        String issuerIdentity,
        String issuerKeyId,
        String issuerPublicKeySha256,
        String policySignature,
        Instant publishedAt
) {
    static final String COLLECTION = "model_promotion_policy_approval_evidence";

    InternalPolicyApprovalEvidenceDocument {
        Objects.requireNonNull(policyKey);
        Objects.requireNonNull(policyCanonicalSha256);
        Objects.requireNonNull(approvalProofSha256);
        Objects.requireNonNull(approvalProof);
        Objects.requireNonNull(approverIdentity);
        Objects.requireNonNull(approverKeyId);
        Objects.requireNonNull(approverPublicKeySha256);
        Objects.requireNonNull(publisherIdentity);
        Objects.requireNonNull(publisherKeyId);
        Objects.requireNonNull(publisherPublicKeySha256);
        if (approvalTrustGeneration < 1) throw new IllegalArgumentException("Approval trust generation is invalid");
        Objects.requireNonNull(approvalTrustSha256);
        Objects.requireNonNull(publisherMandate);
        Objects.requireNonNull(mandatedScope);
        Objects.requireNonNull(mandatedUse);
        Objects.requireNonNull(issuerIdentity);
        Objects.requireNonNull(issuerKeyId);
        Objects.requireNonNull(issuerPublicKeySha256);
        Objects.requireNonNull(policySignature);
        Objects.requireNonNull(publishedAt);
    }
}
