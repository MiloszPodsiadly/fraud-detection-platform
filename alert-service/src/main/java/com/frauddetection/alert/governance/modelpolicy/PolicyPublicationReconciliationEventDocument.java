package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One immutable operator receipt for restoring the acknowledgement of an already signed policy. */
@Document(PolicyPublicationReconciliationEventDocument.COLLECTION)
public record PolicyPublicationReconciliationEventDocument(
        @Id String operationId,
        String publicationRequestId,
        String proposalId,
        String actorId,
        String actorIssuer,
        String actorSubject,
        String draftSha256,
        String policyCanonicalSha256,
        Instant publishedAt,
        Instant reconciledAt
) {
    public static final String COLLECTION = "model_policy_publication_reconciliations";
}
