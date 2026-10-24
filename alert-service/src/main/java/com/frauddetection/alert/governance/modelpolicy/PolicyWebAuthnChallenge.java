package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(PolicyWebAuthnChallenge.COLLECTION)
public record PolicyWebAuthnChallenge(
        @Id String id,
        String kind,
        String actorId,
        String issuer,
        String subject,
        String operationId,
        String proposalId,
        long expectedVersion,
        String draftSha256,
        String policyId,
        long revision,
        String institutionalScope,
        String environment,
        String intendedUse,
        String decision,
        String nonce,
        String requestJson,
        Instant expiresAt,
        boolean used,
        String rationale,
        String registrationGrantId,
        String registrationGrantPurpose,
        String registrationGrantSha256
) {
    public static final String COLLECTION = "model_policy_webauthn_challenges";
}
