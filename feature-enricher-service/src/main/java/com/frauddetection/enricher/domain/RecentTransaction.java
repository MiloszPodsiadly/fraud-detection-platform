package com.frauddetection.enricher.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record RecentTransaction(
        String transactionId,
        Instant transactionTimestamp,
        BigDecimal amount,
        String currency,
        BigDecimal amountPln
) {
}
