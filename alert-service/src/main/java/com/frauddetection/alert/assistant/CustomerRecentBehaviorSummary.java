package com.frauddetection.alert.assistant;

import com.frauddetection.common.events.model.Money;

import java.time.Instant;

public record CustomerRecentBehaviorSummary(
        String customerId,
        String customerSegment,
        Integer accountAgeDays,
        Integer recentTransactionCount,
        Money recentAmountSumPln,
        Double transactionVelocityPerMinute,
        Integer merchantFrequency7d,
        Boolean deviceNovelty,
        Boolean countryMismatch,
        Boolean proxyOrVpnDetected,
        Instant lastKnownTransactionAt
) {
}
