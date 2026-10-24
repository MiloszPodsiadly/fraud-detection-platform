package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;

public record ModelPromotionPolicyPublicationReceipt(
        String policyId,
        long revision,
        String canonicalSha256,
        String issuerIdentity,
        String keyId,
        Instant publishedAt,
        boolean alreadyPublished
) {
}
