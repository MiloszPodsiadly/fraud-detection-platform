package com.frauddetection.alert.governance.modelpromotion;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
final class EmptyModelPromotionPolicyStore implements ModelPromotionPolicyStore {

    @Override
    public Optional<ModelPromotionPolicy> findPolicy(
            ModelGovernanceScope governanceScope,
            ModelPromotionIntendedUse intendedUse
    ) {
        return Optional.empty();
    }
}
