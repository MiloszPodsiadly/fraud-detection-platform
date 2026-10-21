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
        String registrationResponseSha256
) {
    public static final String COLLECTION = "model_policy_credential_enrollment_grants";

    public enum Purpose {
        FIRST, ADDITIONAL, LOST_DEVICE_RECOVERY, COMPROMISE_RECOVERY,
        LOST_DEVICE_REVOCATION, COMPROMISE_REVOCATION
    }
}
