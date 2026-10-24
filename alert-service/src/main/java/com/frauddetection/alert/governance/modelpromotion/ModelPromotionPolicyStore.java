package com.frauddetection.alert.governance.modelpromotion;

import java.util.Optional;
import java.time.Instant;

public interface ModelPromotionPolicyStore {

    Optional<ModelPromotionPolicy> findPolicy(
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse,
            Instant assessmentTime
    );
}
