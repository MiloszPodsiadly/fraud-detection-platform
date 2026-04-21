package com.frauddetection.enricher.domain;

import com.frauddetection.common.events.model.Money;

import java.util.List;
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
        List<String> featureFlags,
        Map<String, Object> featureSnapshot
) {
}
