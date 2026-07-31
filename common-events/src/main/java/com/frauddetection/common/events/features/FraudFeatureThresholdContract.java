package com.frauddetection.common.events.features;

import java.math.BigDecimal;

public final class FraudFeatureThresholdContract {
    public static final int HIGH_VELOCITY_TRANSACTION_COUNT = 5;
    public static final BigDecimal RAPID_TRANSFER_PLN_THRESHOLD = BigDecimal.valueOf(20_000);

    private FraudFeatureThresholdContract() {
    }
}
