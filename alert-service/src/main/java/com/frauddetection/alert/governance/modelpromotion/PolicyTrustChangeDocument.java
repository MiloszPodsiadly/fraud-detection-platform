package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Approved change is an installation request, never a live trust registry. */
@Document(PolicyTrustChangeDocument.COLLECTION)
public record PolicyTrustChangeDocument(@Id String id,
        PolicyTrustChangeService.Intent intent, String intentSha256,
        long baseGeneration, String baseRegistrySha256,
        String candidateSha256, boolean affectsProduction,
        String requestedBy, String requesterCredentialId, String requestAssertionSha256, String requestProofId,
        Instant requestedAt, String status,
        String reviewedBy, String reviewerCredentialId, String reviewAssertionSha256, String reviewProofId,
        Instant reviewedAt) {
    public static final String COLLECTION = "model_policy_trust_change_requests";

    public PolicyTrustChangeDocument(String id, PolicyTrustChangeService.Intent intent, String intentSha256,
            long baseGeneration, String baseRegistrySha256,
            String requestedBy, String requesterCredentialId, String requestAssertionSha256,
            String requestProofId, Instant requestedAt, String status,
            String reviewedBy, String reviewerCredentialId, String reviewAssertionSha256,
            String reviewProofId, Instant reviewedAt) {
        this(id, intent, intentSha256, baseGeneration, baseRegistrySha256,
                intent.candidateRegistrySha256(),
                intent.environment() == ModelGovernanceEnvironment.PRODUCTION,
                requestedBy, requesterCredentialId,
                requestAssertionSha256, requestProofId, requestedAt, status, reviewedBy,
                reviewerCredentialId, reviewAssertionSha256, reviewProofId, reviewedAt);
    }
}
