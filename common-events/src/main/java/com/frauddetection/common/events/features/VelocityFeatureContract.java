package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

public final class VelocityFeatureContract {
    public static final Duration CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW = Duration.ofMinutes(1);
    public static final String CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT =
            CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW.toString();
    public static final int MAX_RECENT_TRANSACTION_COUNT = 1_000_000;
    public static final double MAX_TRANSACTION_VELOCITY_PER_MINUTE = 1_000_000.0d;
    public static final BigDecimal MAX_RECENT_AMOUNT_SUM_PLN = new BigDecimal("999999999999.99");
    public static final double RATE_CONSISTENCY_TOLERANCE = 0.0001d;

    private VelocityFeatureContract() {
    }

    public static double expectedRatePerMinute(int recentTransactionCount) {
        if (recentTransactionCount < 0 || recentTransactionCount > MAX_RECENT_TRANSACTION_COUNT) {
            throw new IllegalArgumentException("recentTransactionCount is outside Velocity feature bounds");
        }
        return BigDecimal.valueOf(recentTransactionCount)
                .divide(
                        BigDecimal.valueOf(CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW.toMinutes()),
                        4,
                        RoundingMode.HALF_UP
                )
                .doubleValue();
    }

    public static boolean isCanonicalWindowText(String value) {
        return CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT.equals(value);
    }

    public static boolean isRateConsistentWithCount(int recentTransactionCount, double transactionVelocityPerMinute) {
        if (!Double.isFinite(transactionVelocityPerMinute)) {
            return false;
        }
        return Math.abs(transactionVelocityPerMinute - expectedRatePerMinute(recentTransactionCount))
                <= RATE_CONSISTENCY_TOLERANCE;
    }

    public static boolean isWithinBounds(int recentTransactionCount) {
        return recentTransactionCount >= 0 && recentTransactionCount <= MAX_RECENT_TRANSACTION_COUNT;
    }

    public static boolean isWithinBounds(double transactionVelocityPerMinute) {
        return Double.isFinite(transactionVelocityPerMinute)
                && transactionVelocityPerMinute >= 0.0d
                && transactionVelocityPerMinute <= MAX_TRANSACTION_VELOCITY_PER_MINUTE;
    }

    public static boolean isWithinBounds(BigDecimal recentAmountSumPln) {
        Objects.requireNonNull(recentAmountSumPln, "recentAmountSumPln is required");
        return recentAmountSumPln.signum() >= 0
                && recentAmountSumPln.compareTo(MAX_RECENT_AMOUNT_SUM_PLN) <= 0;
    }
}
