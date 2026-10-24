package com.frauddetection.alert.governance.modelpolicy;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Immutable;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One protected installation authorization can be consumed only once across all instances. */
@Document(PolicyCredentialBootstrapClaimDocument.COLLECTION)
@Immutable
public record PolicyCredentialBootstrapClaimDocument(@Id String id, String requestId,
        String approvedBy, Instant consumedAt) {
    public static final String COLLECTION = "model_policy_credential_bootstrap_claims";
}
