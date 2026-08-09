package com.frauddetection.common.events.features;

import java.math.BigDecimal;
import java.util.Objects;

public final class FraudFeatureThresholdContract {
    public static final int RAPID_TRANSFER_MIN_COUNT = 2;
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
