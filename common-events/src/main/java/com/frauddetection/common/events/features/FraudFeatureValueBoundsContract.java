package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

public final class FraudFeatureValueBoundsContract {
    public static final Duration CANONICAL_RECENT_TRANSACTION_WINDOW = Duration.ofMinutes(1);
    public static final String CANONICAL_RECENT_TRANSACTION_WINDOW_TEXT =
            CANONICAL_RECENT_TRANSACTION_WINDOW.toString();
    public static final int MAX_RECENT_TRANSACTION_COUNT = 1_000_000;
    public static final double MAX_TRANSACTION_VELOCITY_PER_MINUTE = 1_000_000.0d;
    public static final double RATE_CONSISTENCY_TOLERANCE = 0.0001d;
    public static final BigDecimal MAX_RECENT_AMOUNT_SUM_PLN = new BigDecimal("999999999999.99");

    private FraudFeatureValueBoundsContract() {
    }

    public static boolean isCanonicalRecentTransactionWindowText(String value) {
        return CANONICAL_RECENT_TRANSACTION_WINDOW_TEXT.equals(value);
    }

    public static boolean isWithinCountBounds(int count) {
        return count >= 0 && count <= MAX_RECENT_TRANSACTION_COUNT;
    }

    public static double expectedRatePerMinute(int recentTransactionCount) {
        if (!isWithinCountBounds(recentTransactionCount)) {
            throw new IllegalArgumentException("recentTransactionCount is outside feature bounds");
        }
        return BigDecimal.valueOf(recentTransactionCount)
                .divide(
                        BigDecimal.valueOf(CANONICAL_RECENT_TRANSACTION_WINDOW.toMinutes()),
                        4,
                        RoundingMode.HALF_UP
                )
                .doubleValue();
    }

    public static boolean isWithinRatePerMinuteBounds(double transactionVelocityPerMinute) {
        return Double.isFinite(transactionVelocityPerMinute)
                && transactionVelocityPerMinute >= 0.0d
                && transactionVelocityPerMinute <= MAX_TRANSACTION_VELOCITY_PER_MINUTE;
    }

    public static boolean isRateConsistentWithCount(int recentTransactionCount, double transactionVelocityPerMinute) {
        if (!Double.isFinite(transactionVelocityPerMinute)) {
            return false;
        }
        return Math.abs(transactionVelocityPerMinute - expectedRatePerMinute(recentTransactionCount))
                <= RATE_CONSISTENCY_TOLERANCE;
    }

    public static boolean isWithinAmountBounds(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        return amount.signum() >= 0 && amount.compareTo(MAX_RECENT_AMOUNT_SUM_PLN) <= 0;
    }
}
