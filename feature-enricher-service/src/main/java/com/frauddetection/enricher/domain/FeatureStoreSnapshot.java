package com.frauddetection.enricher.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record FeatureStoreSnapshot(
        int recentTransactionCount,
        BigDecimal recentAmountSum,
        int merchantFrequency7d,
        Instant lastTransactionTimestamp,
        boolean knownDevice
) {
}
