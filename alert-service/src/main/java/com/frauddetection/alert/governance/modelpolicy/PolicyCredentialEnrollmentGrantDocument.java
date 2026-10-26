package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A two-operator authorization; only a hash of the one-time bearer secret is retained. */
@Document(PolicyCredentialEnrollmentGrantDocument.COLLECTION)
public record PolicyCredentialEnrollmentGrantDocument(
        @Id String id,
        String targetActorId,
        String targetIssuer,
        String targetSubject,
        Purpose purpose,
        String caseReference,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String secretSha256,
        Instant expiresAt,
        Instant consumedAt,
        String credentialId,
        String registrationResponseSha256,
        Instant rejectedAt,
        Instant claimedAt,
        String reviewOperationId,
        String reviewProofId,
        String reviewCredentialId,
        String reviewAssertionSha256,
        String bootstrapAuthorizationId,
        Instant requestExpiresAt,
        Instant plannedGrantExpiresAt,
        Instant revokedAt,
        String revokedBy,
        String revokedByIssuer,
        String revokedBySubject,
        String revocationOperationId,
        String revocationReason,
        String revocationCaseReference,
        RevocationKind revocationKind
) {
    public static final String COLLECTION = "model_policy_credential_enrollment_grants";

    public enum Purpose {
        FIRST, ADDITIONAL, LOST_DEVICE_RECOVERY, COMPROMISE_RECOVERY,
        LOST_DEVICE_REVOCATION, COMPROMISE_REVOCATION
    }

    public enum RevocationKind { ADMINISTRATIVE, SECURITY_INCIDENT }

    /** Existing grants predate explicit revocation metadata and remain readable. */
    public PolicyCredentialEnrollmentGrantDocument(String id, String targetActorId,
            String targetIssuer, String targetSubject, Purpose purpose, String caseReference,
            String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt,
            String secretSha256, Instant expiresAt, Instant consumedAt, String credentialId,
            String registrationResponseSha256, Instant rejectedAt, Instant claimedAt,
            String reviewOperationId, String reviewProofId, String reviewCredentialId,
            String reviewAssertionSha256, String bootstrapAuthorizationId,
            Instant requestExpiresAt, Instant plannedGrantExpiresAt) {
        this(id, targetActorId, targetIssuer, targetSubject, purpose, caseReference,
                requestedBy, requestedAt, approvedBy, approvedAt, secretSha256, expiresAt,
                consumedAt, credentialId, registrationResponseSha256, rejectedAt, claimedAt,
                reviewOperationId, reviewProofId, reviewCredentialId, reviewAssertionSha256,
                bootstrapAuthorizationId, requestExpiresAt, plannedGrantExpiresAt,
                null, null, null, null, null, null, null, null);
    }
}
