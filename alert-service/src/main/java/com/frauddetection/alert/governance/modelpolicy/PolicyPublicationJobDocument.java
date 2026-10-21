package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDraft;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Immutable publication intent plus a bounded, recoverable worker lease. */
@Document(PolicyPublicationJobDocument.COLLECTION)
public record PolicyPublicationJobDocument(
        @Id String id,
        String proposalId,
        long proposalVersion,
        ModelPromotionPolicyDraft draft,
        String draftSha256,
        String authorActorId,
        String submissionEvidenceReference,
        Instant submittedAt,
        String approverActorId,
        String approvalEvidenceReference,
        Instant decidedAt,
        String requestingActorId,
        Instant requestedAt,
        State state,
        String leaseOwner,
        Instant leaseUntil,
        int attempts,
        String publishedPolicySha256,
        Instant publishedAt,
        String failureCode
) {
    public static final String COLLECTION = "model_policy_publication_jobs";

    public enum State { PENDING, CLAIMED, PUBLISHED, RETRYABLE, BLOCKED }
}
