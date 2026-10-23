package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

/** One approved candidate per registry generation, even when requests race. */
@Document(PolicyTrustGenerationClaimDocument.COLLECTION)
@Immutable
public record PolicyTrustGenerationClaimDocument(@Id String id, String requestId,
        String baseSha256, String candidateSha256) {
    public static final String COLLECTION = "model_policy_trust_generation_claims";
}
