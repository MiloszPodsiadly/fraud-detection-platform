package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Internal, append-only snapshot of one accepted governance assertion. Never return from an HTTP read model. */
@Document(GovernanceBoundWebAuthnProof.COLLECTION)
@Immutable
public record GovernanceBoundWebAuthnProof(
        @Id String id,
        String version,
        String namespace,
        String contractJson,
        String contractSha256,
        String requestJson,
        String assertionResponseBase64,
        String assertionSha256,
        String actorId,
        String issuer,
        String subject,
        String credentialId,
        String userHandle,
        String publicKeyCose,
        String rpId,
        String origin,
        boolean userPresenceRequired,
        boolean userVerificationRequired,
        long previousSignatureCount,
        long signatureCount,
        Instant expiresAt,
        Instant acceptedAt
) {
    public static final String COLLECTION = "governance_bound_webauthn_proofs";
    public static final String VERSION = "governance-bound-webauthn-proof-v1";
}
