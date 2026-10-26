package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Immutable receipt committed with the reviewed proposal, lifecycle transition and local audit. */
@Document(ModelPromotionWebAuthnReviewEvidence.COLLECTION)
public record ModelPromotionWebAuthnReviewEvidence(
        @Id String challengeId,
        String proposalId,
        String operationId,
        String approvalId,
        ModelPromotionApproval.Outcome outcome,
        String rationaleSha256,
        String actorId,
        String issuer,
        String subject,
        String credentialId,
        String contractSha256,
        String assertionSha256,
        long signatureCount,
        Instant acceptedAt
) {
    public static final String COLLECTION = "model_promotion_webauthn_review_evidence";
}
