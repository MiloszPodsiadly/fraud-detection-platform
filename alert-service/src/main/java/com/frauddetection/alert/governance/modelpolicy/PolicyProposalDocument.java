package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDraft;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(PolicyProposalDocument.COLLECTION)
public record PolicyProposalDocument(
        @Id String id,
        ModelPromotionPolicyDraft draft,
        String draftSha256,
        PolicyGovernanceState status,
        long version,
        String authorActorId,
        String submittingActorId,
        Instant submittedAt,
        String reviewingActorId,
        Instant decidedAt,
        String approvalEvidenceReference,
        String publicationRequestId,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String COLLECTION = "model_policy_governance_proposals";

    public PolicyProposalDocument next(ModelPromotionPolicyDraft nextDraft, String nextSha,
            PolicyGovernanceState nextStatus, String submitter, Instant submitted,
            String reviewer, Instant decisionTime, String evidence, String requestId, Instant now) {
        return new PolicyProposalDocument(id, nextDraft, nextSha, nextStatus, version + 1,
                authorActorId, submitter, submitted, reviewer, decisionTime, evidence, requestId,
                createdAt, now);
    }
}
