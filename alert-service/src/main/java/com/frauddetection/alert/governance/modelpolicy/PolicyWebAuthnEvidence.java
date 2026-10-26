package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Durable user assertion receipt, committed with the governance transition. */
@Document(PolicyWebAuthnEvidence.COLLECTION)
public record PolicyWebAuthnEvidence(
        @Id String id,
        String operationId,
        String proposalId,
        String action,
        String actorId,
        String issuer,
        String subject,
        String credentialId,
        String draftSha256,
        String assertionSha256,
        String assertionResponseJson,
        String challengeSha256,
        long signatureCount,
        Instant acceptedAt
) {
    public static final String COLLECTION = "model_policy_webauthn_evidence";
}
