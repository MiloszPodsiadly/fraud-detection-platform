package com.frauddetection.alert.governance.modelpromotion;

import com.frauddetection.alert.governance.ExactModelArtifactIdentity;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = ModelPromotionDecisionDocument.COLLECTION)
@Immutable
public record ModelPromotionDecisionDocument(
        @Id String operationKey,
        @Field("aggregate_key") String aggregateKey,
        @Field("operation_id") String operationId,
        @Field("command_sha256") String commandSha256,
        @Field("actor_id") String actorId,
        @Field("authorization_fingerprint") String authorizationFingerprint,
        @Field("event_type") EventType eventType,
        @Field("artifact_identity") ExactModelArtifactIdentity artifactIdentity,
        @Field("governance_scope") ModelGovernanceScope governanceScope,
        @Field("intended_use") ModelPromotionIntendedUse intendedUse,
        @Field("source_state") ModelPromotionLifecycleState sourceState,
        @Field("resulting_state") ModelPromotionLifecycleState resultingState,
        @Field("resulting_revision") long resultingRevision,
        @Field("decision_id") String decisionId,
        @Field("policy_snapshot") ModelPromotionPolicy policySnapshot,
        @Field("assessment_snapshot") ModelPromotionAssessment assessmentSnapshot,
        ModelPromotionProposal proposal,
        ModelPromotionApproval approval,
        @Field("local_audit") LocalAuditEvidence localAudit,
        @Field("committed_at") Instant committedAt
) {

    public static final String COLLECTION = "model_promotion_decisions";
    public static final String OPERATION_INDEX = "model_promotion_operation_unique_idx";
    public static final String DECISION_INDEX = "model_promotion_decision_unique_idx";
    public static final String HISTORY_INDEX = "model_promotion_history_v2_idx";

    ModelPromotionMutationResult toResult() {
        ModelPromotionAggregate aggregate = new ModelPromotionAggregate(
                artifactIdentity,
                governanceScope,
                intendedUse,
                resultingState,
                resultingRevision,
                decisionId
        );
        return new ModelPromotionMutationResult(aggregate, decisionId, operationId, committedAt, false);
    }

    public enum EventType {
        CANDIDATE_REGISTERED,
        TRANSITION_PROPOSAL_REJECTED,
        LIFECYCLE_TRANSITION_ACCEPTED
    }

    public record LocalAuditEvidence(
            String auditId,
            String action,
            String outcome,
            String actorId,
            String authorizationFingerprint,
            Instant occurredAt
    ) {
    }
}
