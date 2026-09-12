package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public final class VelocityFeatureContract {
    public static final Duration CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW =
            FraudFeatureValueBoundsContract.RULES_V1_CANONICAL_WINDOW;
    public static final String CANONICAL_RECENT_TRANSACTION_COUNT_WINDOW_TEXT =
            FraudFeatureValueBoundsContract.RULES_V1_CANONICAL_WINDOW_TEXT;
    public static final int MAX_RECENT_TRANSACTION_COUNT =
            FraudFeatureValueBoundsContract.MAX_RECENT_TRANSACTION_COUNT;
    public static final double MAX_TRANSACTION_VELOCITY_PER_MINUTE =
            FraudFeatureValueBoundsContract.MAX_TRANSACTION_VELOCITY_PER_MINUTE;
    public static final BigDecimal MAX_RECENT_AMOUNT_SUM_PLN =
            FraudFeatureValueBoundsContract.MAX_RECENT_AMOUNT_SUM_PLN;
    public static final double RATE_CONSISTENCY_TOLERANCE =
            FraudFeatureValueBoundsContract.RATE_CONSISTENCY_TOLERANCE;

    private VelocityFeatureContract() {
    }

    public static double expectedRatePerMinute(int recentTransactionCount) {
        return FraudFeatureValueBoundsContract.expectedRatePerMinute(recentTransactionCount);
    }

    public static boolean isCanonicalWindowText(String value) {
        return FraudFeatureValueBoundsContract.isRulesV1CanonicalWindowText(value);
    }

    public static boolean isRateConsistentWithCount(int recentTransactionCount, double transactionVelocityPerMinute) {
        return FraudFeatureValueBoundsContract.isRateConsistentWithCount(
                recentTransactionCount,
                transactionVelocityPerMinute
        );
    }

    public static boolean isWithinBounds(int recentTransactionCount) {
        return FraudFeatureValueBoundsContract.isWithinCountBounds(recentTransactionCount);
    }

    public static boolean isWithinBounds(double transactionVelocityPerMinute) {
        return FraudFeatureValueBoundsContract.isWithinRatePerMinuteBounds(transactionVelocityPerMinute);
    }

    public static boolean isWithinBounds(BigDecimal recentAmountSumPln) {
        Objects.requireNonNull(recentAmountSumPln, "recentAmountSumPln is required");
        return FraudFeatureValueBoundsContract.isWithinAmountBounds(recentAmountSumPln);
    }
}
