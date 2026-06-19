package com.frauddetection.alert.domain;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;

import java.time.Instant;
import java.util.List;

public record ScoredTransaction(
        String transactionId,
        String customerId,
        String correlationId,
        Instant transactionTimestamp,
        Instant scoredAt,
        Money transactionAmount,
        MerchantInfo merchantInfo,
        Double fraudScore,
        RiskLevel riskLevel,
        Boolean alertRecommended,
        List<String> reasonCodes,
        AnalystRecommendationResult analystRecommendation
) {
    public ScoredTransaction(
            String transactionId,
            String customerId,
            String correlationId,
            Instant transactionTimestamp,
            Instant scoredAt,
            Money transactionAmount,
            MerchantInfo merchantInfo,
            Double fraudScore,
            RiskLevel riskLevel,
            Boolean alertRecommended,
            List<String> reasonCodes
    ) {
        this(
                transactionId,
                customerId,
                correlationId,
                transactionTimestamp,
                scoredAt,
                transactionAmount,
                merchantInfo,
                fraudScore,
                riskLevel,
                alertRecommended,
                reasonCodes,
                null
        );
    }
}
