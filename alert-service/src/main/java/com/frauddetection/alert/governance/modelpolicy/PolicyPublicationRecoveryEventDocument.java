package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Append-only operator recovery audit; never substitutes for the original approval. */
@Document(PolicyPublicationRecoveryEventDocument.COLLECTION)
public record PolicyPublicationRecoveryEventDocument(
        @Id String operationId, String publicationRequestId, String proposalId,
        String actorId, String actorIssuer, String actorSubject, String draftSha256,
        int previousAttempts, int recoveryOrdinal, String previousFailureCode,
        Instant requestedAt) {
    public static final String COLLECTION = "model_policy_publication_recovery_events";
}
