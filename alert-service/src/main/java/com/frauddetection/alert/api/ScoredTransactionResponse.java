package com.frauddetection.alert.api;

import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;

public record ScoredTransactionResponse(
        String transactionId,
        String customerId,
        String correlationId,
        Instant transactionTimestamp,
        Instant scoredAt,
        MoneyResponse transactionAmount,
        MerchantInfoResponse merchantInfo,
        Double fraudScore,
        RiskLevel riskLevel,
        Boolean alertRecommended,
        List<String> reasonCodes
) {
}
