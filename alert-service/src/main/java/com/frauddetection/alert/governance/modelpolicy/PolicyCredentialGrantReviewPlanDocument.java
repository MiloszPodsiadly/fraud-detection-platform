package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Immutable server-owned expiry bound into one credential grant review assertion. */
@Document(PolicyCredentialGrantReviewPlanDocument.COLLECTION)
@Immutable
public record PolicyCredentialGrantReviewPlanDocument(@Id String id, String requestId,
        String reviewerId, PolicyCredentialGrantReviewService.Decision decision,
        Instant grantExpiresAt) {
    public static final String COLLECTION = "model_policy_credential_grant_review_plans";
}
