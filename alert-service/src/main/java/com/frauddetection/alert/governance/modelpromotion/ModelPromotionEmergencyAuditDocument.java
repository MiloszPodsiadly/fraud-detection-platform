package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Append-only local incident authorization and consumption ledger. */
@Document(ModelPromotionEmergencyAuditDocument.COLLECTION)
@Immutable
public record ModelPromotionEmergencyAuditDocument(
        @Id String id,
        String authorizationId,
        String proposalId,
        String action,
        String actorId,
        String issuer,
        String subject,
        String intentSha256,
        String incidentId,
        Instant occurredAt
) {
    public static final String COLLECTION = "model_promotion_emergency_audit";
}
