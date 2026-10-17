package com.frauddetection.alert.governance.modelpromotion;

import java.util.Optional;

public interface ModelPromotionPolicyStore {

    Optional<ModelPromotionPolicy> findPolicy(
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse
    );
}
