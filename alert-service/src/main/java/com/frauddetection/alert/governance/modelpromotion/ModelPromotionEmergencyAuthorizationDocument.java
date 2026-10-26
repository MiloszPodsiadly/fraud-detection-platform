package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Internal, exact-intent emergency grant; only state and one-way token digest are mutable. */
@Document(ModelPromotionEmergencyAuthorizationDocument.COLLECTION)
public record ModelPromotionEmergencyAuthorizationDocument(
        @Id String id,
        String version,
        String proposalId,
        String proposalSha256,
        ModelPromotionTransitionProposal transition,
        ModelPromotionLifecycleState currentState,
        long currentRevision,
        String reviewOperationId,
        String approvalId,
        String requesterActorId,
        String requesterIssuer,
        String requesterSubject,
        String incidentId,
        String incidentEvidenceReference,
        String reason,
        String reasonSha256,
        String intentSha256,
        Instant createdAt,
        Instant expiresAt,
        Status status,
        String authorizedByActorId,
        String authorizedByIssuer,
        String authorizedBySubject,
        String authorizationRationale,
        Instant authorizedAt,
        String tokenSha256,
        Instant consumedAt,
        Instant revokedAt,
        Instant claimedAt,
        String requesterAcr,
        Instant requesterAuthenticatedAt,
        String authorizerAcr,
        Instant authorizerAuthenticatedAt,
        String authorizationEvidenceSha256
) {
    public static final String COLLECTION = "model_promotion_emergency_authorizations";
    public static final String VERSION = "model-promotion-emergency-authorization-v1";

    public enum Status { REQUESTED, AUTHORIZED, CLAIMED, CONSUMED, REVOKED }
}
