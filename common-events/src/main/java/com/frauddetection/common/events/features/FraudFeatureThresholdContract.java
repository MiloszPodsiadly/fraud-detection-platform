package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public final class FraudFeatureThresholdContract {
    /**
     * Velocity V1 observes recent transaction count and transactionVelocityPerMinute over exactly PT1M.
     * Changing this window changes feature meaning and requires a versioned Velocity contract update.
     */
    public static final Duration VELOCITY_V1_OBSERVATION_WINDOW = Duration.ofMinutes(1);
    public static final int RAPID_TRANSFER_MIN_COUNT = 2;
    public static final int HIGH_VELOCITY_TRANSACTION_COUNT = 5;
    public static final BigDecimal RAPID_TRANSFER_PLN_THRESHOLD = BigDecimal.valueOf(20_000);

    private FraudFeatureThresholdContract() {
    }

    public static boolean isRapidTransferPlnBurst(int rapidTransferCount, BigDecimal rapidTransferTotalPln) {
        Objects.requireNonNull(rapidTransferTotalPln, "rapidTransferTotalPln is required");
        if (rapidTransferCount < 0 || rapidTransferTotalPln.signum() < 0) {
            throw new IllegalArgumentException("rapid transfer facts must be non-negative");
        }
        return rapidTransferCount >= RAPID_TRANSFER_MIN_COUNT
                && rapidTransferTotalPln.compareTo(RAPID_TRANSFER_PLN_THRESHOLD) >= 0;
    }
}
