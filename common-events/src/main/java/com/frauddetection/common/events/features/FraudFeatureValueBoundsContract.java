package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public final class FraudFeatureValueBoundsContract {
    public static final Duration RULES_V1_CANONICAL_WINDOW = Duration.ofMinutes(1);
    public static final String RULES_V1_CANONICAL_WINDOW_TEXT = RULES_V1_CANONICAL_WINDOW.toString();
    public static final int MAX_RECENT_TRANSACTION_COUNT = 1_000_000;
    public static final BigDecimal MAX_RECENT_AMOUNT_SUM_PLN = new BigDecimal("999999999999.99");

    private FraudFeatureValueBoundsContract() {
    }

    public static boolean isRulesV1CanonicalWindowText(String value) {
        return RULES_V1_CANONICAL_WINDOW_TEXT.equals(value);
    }

    public static boolean isWithinCountBounds(int count) {
        return count >= 0 && count <= MAX_RECENT_TRANSACTION_COUNT;
    }

    public static boolean isWithinAmountBounds(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount is required");
        return amount.signum() >= 0 && amount.compareTo(MAX_RECENT_AMOUNT_SUM_PLN) <= 0;
    }
}
