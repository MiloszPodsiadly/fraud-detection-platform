package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Domain-separated assertion challenge used by governance workflows through the shared WebAuthn verifier. */
@Document(GovernanceWebAuthnBoundChallenge.COLLECTION)
public record GovernanceWebAuthnBoundChallenge(
        @Id String id,
        String namespace,
        String actorId,
        String issuer,
        String subject,
        String contractSha256,
        String nonce,
        String requestJson,
        Instant expiresAt,
        boolean used
) {
    public static final String COLLECTION = "governance_webauthn_bound_challenges";
}
