package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Public credential material only. The authenticator private key never reaches this service. */
@Document(PolicyWebAuthnCredential.COLLECTION)
public record PolicyWebAuthnCredential(
        @Id String id,
        String actorId,
        String issuer,
        String subject,
        String userHandle,
        String publicKeyCose,
        long signatureCount,
        boolean revoked,
        Instant createdAt,
        Instant updatedAt,
        String status,
        String enrollmentMethod,
        String grantId,
        String approvedBy,
        String caseReference,
        String label
) {
    public static final String COLLECTION = "model_policy_webauthn_credentials";

    public boolean canSignNewAction() {
        return !revoked && "ACTIVE".equals(status);
    }

    /** Operational retirement differs from compromise when relying on a published approval. */
    public boolean canSupportPublishedApproval() {
        return canSignNewAction() || revoked && ("REVOKED".equals(status) || "LOST".equals(status));
    }
}
