package com.frauddetection.scoring.engine.velocity;

import com.frauddetection.scoring.features.FeatureSnapshotValue;

import java.math.BigDecimal;
import java.util.List;

record VelocityInputs(
        FeatureSnapshotValue<Integer> recentTransactionCount,
        FeatureSnapshotValue<String> recentTransactionCountWindow,
        FeatureSnapshotValue<BigDecimal> recentAmountSumPln,
        FeatureSnapshotValue<Double> transactionVelocityPerMinute
) {
    List<FeatureSnapshotValue<?>> values() {
        return List.of(
                recentTransactionCount,
                recentTransactionCountWindow,
                recentAmountSumPln,
                transactionVelocityPerMinute
        );
    }
}
