package com.frauddetection.scoring.engine.velocity;

import java.math.BigDecimal;

record ValidatedVelocityInputs(
        int recentTransactionCount,
        BigDecimal recentAmountSumPln,
        double transactionVelocityPerMinute
) {
}
