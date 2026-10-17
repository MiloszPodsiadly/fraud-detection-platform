package com.frauddetection.alert.governance.modelpromotion;

import java.util.Objects;

public record ModelPromotionChampionKey(
        ModelGovernanceScope governanceScope,
        ModelPromotionIntendedUse intendedUse
) {

    public ModelPromotionChampionKey {
        Objects.requireNonNull(governanceScope, "governanceScope is required");
        Objects.requireNonNull(intendedUse, "intendedUse is required");
    }
}
