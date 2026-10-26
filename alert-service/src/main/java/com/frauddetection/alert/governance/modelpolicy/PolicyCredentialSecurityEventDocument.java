package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Append-only security audit record committed with each credential lifecycle mutation. */
@Document(PolicyCredentialSecurityEventDocument.COLLECTION)
public record PolicyCredentialSecurityEventDocument(
        @Id String id,
        String action,
        String targetActorId,
        String targetIssuer,
        String targetSubject,
        String actorId,
        String reviewerActorId,
        String approvalReference,
        String credentialId,
        Instant occurredAt,
        String grantId,
        String operationId,
        String reason,
        String caseReference,
        String originalApprovalReference,
        PolicyCredentialEnrollmentGrantDocument.RevocationKind revocationKind
) {
    public static final String COLLECTION = "model_policy_credential_security_events";

    public PolicyCredentialSecurityEventDocument(String id, String action,
            String targetActorId, String targetIssuer, String targetSubject,
            String actorId, String reviewerActorId, String approvalReference,
            String credentialId, Instant occurredAt) {
        this(id, action, targetActorId, targetIssuer, targetSubject, actorId,
                reviewerActorId, approvalReference, credentialId, occurredAt,
                null, null, null, null, null, null);
    }
}
