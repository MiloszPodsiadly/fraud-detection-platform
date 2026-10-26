package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(PolicyTrustChangeAuditDocument.COLLECTION)
public record PolicyTrustChangeAuditDocument(@Id String id, String requestId,
        String action, String actorId, String intentSha256, String assertionSha256,
        String proofId, Instant occurredAt) {
    public static final String COLLECTION = "model_policy_trust_change_audit";
}
