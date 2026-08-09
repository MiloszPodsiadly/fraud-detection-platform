package com.frauddetection.scoring.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

record RulesV1Contribution(
        double weight,
        List<RulesV1ContributionSource> sources
) {
    RulesV1Contribution {
        weight = BigDecimal.valueOf(weight).setScale(2, RoundingMode.HALF_UP).doubleValue();
        sources = List.copyOf(sources);
    }

    static RulesV1Contribution none() {
        return new RulesV1Contribution(0.0d, List.of());
    }
}
