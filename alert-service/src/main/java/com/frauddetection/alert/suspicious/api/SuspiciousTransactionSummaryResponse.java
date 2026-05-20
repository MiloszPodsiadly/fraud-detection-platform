package com.frauddetection.alert.suspicious.api;

import java.time.Instant;

public record SuspiciousTransactionSummaryResponse(
        long totalSuspiciousTransactions,
        SuspiciousTransactionSummaryFreshness freshness,
        Instant cachedAt,
        Instant expiresAt
) {

    public SuspiciousTransactionSummaryResponse {
        if (freshness == null) {
            freshness = SuspiciousTransactionSummaryFreshness.FRESH;
        }
    }

    public static SuspiciousTransactionSummaryResponse fresh(long total, Instant cachedAt, Instant expiresAt) {
        return new SuspiciousTransactionSummaryResponse(
                total,
                SuspiciousTransactionSummaryFreshness.FRESH,
                cachedAt,
                expiresAt
        );
    }

    public static SuspiciousTransactionSummaryResponse stale(long total, Instant cachedAt, Instant expiresAt) {
        return new SuspiciousTransactionSummaryResponse(
                total,
                SuspiciousTransactionSummaryFreshness.STALE,
                cachedAt,
                expiresAt
        );
    }

    public static SuspiciousTransactionSummaryResponse unavailable() {
        return new SuspiciousTransactionSummaryResponse(
                0L,
                SuspiciousTransactionSummaryFreshness.UNAVAILABLE,
                null,
                null
        );
    }
}
