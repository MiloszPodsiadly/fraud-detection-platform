package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Objects;

@Document(collection = ModelPromotionProposalDocument.COLLECTION)
@CompoundIndexes({
        @CompoundIndex(name = ModelPromotionProposalDocument.OPERATION_INDEX,
                def = "{'aggregate_key':1,'operation_id':1}", unique = true),
        @CompoundIndex(name = ModelPromotionProposalDocument.PENDING_REVIEW_INDEX,
                def = "{'aggregate_key':1,'review_status':1,'submitted_at':-1}"),
        @CompoundIndex(name = ModelPromotionProposalDocument.HISTORY_INDEX,
                def = "{'aggregate_key':1,'submitted_at':-1,'_id':1}")
})
public record ModelPromotionProposalDocument(
        @Id String proposalId,
        @Field("aggregate_key") String aggregateKey,
        @Field("operation_id") String operationId,
        @Field("command_sha256") String commandSha256,
        @Field("actor_id") String actorId,
        @Field("authorization_fingerprint") String authorizationFingerprint,
        ModelPromotionTransitionProposal transition,
        @Field("policy_snapshot") ModelPromotionPolicy policySnapshot,
        @Field("assessment_snapshot") ModelPromotionAssessment assessmentSnapshot,
        ModelPromotionProposal proposal,
        @Field("review_status") ReviewStatus reviewStatus,
        ModelPromotionApproval approval,
        @Field("review_operation_id") String reviewOperationId,
        @Field("review_command_sha256") String reviewCommandSha256,
        @Field("reviewer_authorization_fingerprint") String reviewerAuthorizationFingerprint,
        @Field("submission_audit") ModelPromotionDecisionDocument.LocalAuditEvidence submissionAudit,
        @Field("review_audit") ModelPromotionDecisionDocument.LocalAuditEvidence reviewAudit,
        @Field("submitted_at") Instant submittedAt,
        @Field("reviewed_at") Instant reviewedAt
) {

    public static final String COLLECTION = "model_promotion_proposals";
    public static final String OPERATION_INDEX = "model_promotion_proposal_operation_unique_idx";
    public static final String PENDING_REVIEW_INDEX = "model_promotion_proposal_pending_review_idx";
    public static final String HISTORY_INDEX = "model_promotion_proposal_history_v1_idx";

    public ModelPromotionProposalDocument {
        require(proposalId, "proposalId");
        require(aggregateKey, "aggregateKey");
        require(operationId, "operationId");
        require(commandSha256, "commandSha256");
        require(actorId, "actorId");
        require(authorizationFingerprint, "authorizationFingerprint");
        Objects.requireNonNull(transition, "transition is required");
        Objects.requireNonNull(proposal, "proposal is required");
        Objects.requireNonNull(reviewStatus, "reviewStatus is required");
        Objects.requireNonNull(submissionAudit, "submissionAudit is required");
        Objects.requireNonNull(submittedAt, "submittedAt is required");
        if (!proposalId.equals(proposal.proposalId())) {
            throw new IllegalArgumentException("proposal identifier does not match the proposal content");
        }
        if (!operationId.equals(transition.operationId())) {
            throw new IllegalArgumentException("operation identifier does not match the proposed transition");
        }
        if (!actorId.equals(proposal.proposedBy())) {
            throw new IllegalArgumentException("proposal actor does not match the submitting actor");
        }
        boolean reviewed = reviewStatus == ReviewStatus.APPROVED || reviewStatus == ReviewStatus.REJECTED;
        if (reviewed != (approval != null)) {
            throw new IllegalArgumentException("review status and approval must be consistent");
        }
        if (reviewed && (reviewOperationId == null || reviewCommandSha256 == null
                || reviewerAuthorizationFingerprint == null || reviewAudit == null || reviewedAt == null)) {
            throw new IllegalArgumentException("completed review details are required");
        }
        if (!reviewed && (approval != null || reviewOperationId != null || reviewCommandSha256 != null
                || reviewerAuthorizationFingerprint != null || reviewAudit != null || reviewedAt != null)) {
            throw new IllegalArgumentException("pending review must not contain completed review details");
        }
    }

    static ModelPromotionProposalDocument pending(
            String aggregateKey,
            String commandSha256,
            ModelPromotionProposalSubmission submission,
            ModelPromotionDecisionDocument.LocalAuditEvidence submissionAudit,
            Instant submittedAt
    ) {
        return new ModelPromotionProposalDocument(
                submission.proposal().proposalId(),
                aggregateKey,
                submission.transition().operationId(),
                commandSha256,
                submission.operationContext().actorId(),
                submission.operationContext().authorizationFingerprint(),
                submission.transition(),
                submission.policySnapshot(),
                submission.assessmentSnapshot(),
                submission.proposal(),
                ReviewStatus.PENDING,
                null,
                null,
                null,
                null,
                submissionAudit,
                null,
                submittedAt,
                null
        );
    }

    ModelPromotionProposalResult toResult(boolean idempotentReplay) {
        return new ModelPromotionProposalResult(
                proposalId,
                reviewStatus,
                proposal,
                approval,
                policySnapshot == null ? null : policySnapshot.canonicalSha256(),
                assessmentSnapshot == null ? null : assessmentSnapshot.canonicalSha256(),
                submittedAt,
                reviewedAt,
                idempotentReplay
        );
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public enum ReviewStatus {
        PENDING,
        REVIEW_IN_PROGRESS,
        APPROVED,
        REJECTED
    }
}
