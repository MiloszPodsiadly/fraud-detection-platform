package com.frauddetection.enricher.domain;

import com.frauddetection.common.events.model.Money;

import java.util.Map;

public record EnrichedTransactionFeatures(
        Integer recentTransactionCount,
        String recentTransactionCountWindow,
        Money recentAmountSum,
        String recentAmountSumWindow,
        Double transactionVelocityPerMinute,
        Integer merchantFrequency7d,
        Boolean deviceNovelty,
        Boolean countryMismatch,
        Boolean proxyOrVpnDetected,
        Map<String, Object> featureSnapshot
) {
}
