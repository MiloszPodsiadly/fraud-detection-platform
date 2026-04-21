package com.frauddetection.enricher.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FeatureStoreSnapshot(
        int recentTransactionCount,
        BigDecimal recentAmountSum,
        BigDecimal recentAmountSumPln,
        List<RecentTransaction> recentTransactions,
        int merchantFrequency7d,
        Instant lastTransactionTimestamp,
        boolean knownDevice
) {
}
