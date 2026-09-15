package com.frauddetection.scoring.service;

import com.frauddetection.scoring.features.FeatureSnapshotValue;

import java.math.BigDecimal;
import java.util.List;

record RulesV2FeatureValues(
        FeatureSnapshotValue<Integer> recentTransactionCount,
        FeatureSnapshotValue<String> recentTransactionCountWindow,
        FeatureSnapshotValue<Double> transactionVelocityPerMinute,
        FeatureSnapshotValue<BigDecimal> recentAmountSumPln,
        FeatureSnapshotValue<String> recentAmountSumWindow,
        FeatureSnapshotValue<BigDecimal> currentTransactionAmountPln,
        FeatureSnapshotValue<Integer> merchantFrequency7d,
        FeatureSnapshotValue<Boolean> deviceNovelty,
        FeatureSnapshotValue<Boolean> countryMismatch,
        FeatureSnapshotValue<Boolean> proxyOrVpnDetected,
        FeatureSnapshotValue<String> currency
) {
    List<FeatureSnapshotValue<?>> values() {
        return List.of(
                recentTransactionCount,
                recentTransactionCountWindow,
                transactionVelocityPerMinute,
                recentAmountSumPln,
                recentAmountSumWindow,
                currentTransactionAmountPln,
                merchantFrequency7d,
                deviceNovelty,
                countryMismatch,
                proxyOrVpnDetected,
                currency
        );
    }
}
