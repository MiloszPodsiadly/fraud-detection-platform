package com.frauddetection.alert.assistant;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.Money;

import java.time.Instant;

public record TransactionSummary(
        String transactionId,
        Instant transactionTimestamp,
        Money amount,
        String merchantId,
        String merchantName,
        String merchantCategory,
        String channel,
        String countryCode,
        Double fraudScore,
        RiskLevel riskLevel
) {
}
