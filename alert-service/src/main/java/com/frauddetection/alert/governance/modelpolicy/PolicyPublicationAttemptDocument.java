package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Durable bounded worker-attempt ledger; a stale CLAIMED outcome remains explicitly uncertain. */
@Document(PolicyPublicationAttemptDocument.COLLECTION)
public record PolicyPublicationAttemptDocument(@Id String id, String publicationRequestId,
        int attempt, String workerId, Instant claimedAt, String outcome, Instant endedAt,
        String failureCode) {
    public static final String COLLECTION = "model_policy_publication_attempts";
}
