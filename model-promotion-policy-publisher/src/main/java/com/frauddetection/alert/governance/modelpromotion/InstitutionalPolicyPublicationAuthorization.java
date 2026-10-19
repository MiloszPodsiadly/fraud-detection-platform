package com.frauddetection.alert.governance.modelpromotion;

import java.time.Instant;
import java.util.Optional;

/**
 * Trusted boundary for independently verified policy approval AND authenticated publisher mandate.
 * Implementations must not derive authority from the proposed policy, an arbitrary CLI argument, or MongoDB.
 */
public interface InstitutionalPolicyPublicationAuthorization {

    Optional<Authorization> authorize(ModelPromotionPolicy.Content content, String canonicalSha256);

    record Authorization(
            String policyId,
            long revision,
            String canonicalSha256,
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            Instant effectiveFrom,
            Instant effectiveTo,
            String authorIdentity,
            String approverIdentity,
            String approvalReference,
            String publisherIdentity,
            String issuerIdentity,
            String keyId
    ) {
    }
}
