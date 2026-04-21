package com.frauddetection.alert.api;

import com.frauddetection.common.events.enums.RiskLevel;

import java.math.BigDecimal;
import java.time.Instant;

public record FraudCaseTransactionResponse(
        String transactionId,
        String correlationId,
        Instant transactionTimestamp,
        MoneyResponse transactionAmount,
        BigDecimal amountPln,
        Double fraudScore,
        RiskLevel riskLevel
) {
}
