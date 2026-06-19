package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.ScoredTransactionDetailResponse;
import com.frauddetection.alert.api.ScoredTransactionResponse;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.common.events.recommendation.AnalystRecommendationResult;
import org.springframework.stereotype.Component;

@Component
public class ScoredTransactionResponseMapper {

    private final AlertResponseMapper alertResponseMapper;

    public ScoredTransactionResponseMapper(AlertResponseMapper alertResponseMapper) {
        this.alertResponseMapper = alertResponseMapper;
    }

    public ScoredTransactionResponse toResponse(ScoredTransaction transaction) {
        return new ScoredTransactionResponse(
                transaction.transactionId(),
                transaction.customerId(),
                transaction.correlationId(),
                transaction.transactionTimestamp(),
                transaction.scoredAt(),
                alertResponseMapper.toMoneyResponse(transaction.transactionAmount()),
                alertResponseMapper.toMerchantInfoResponse(transaction.merchantInfo()),
                transaction.fraudScore(),
                transaction.riskLevel(),
                transaction.alertRecommended(),
                transaction.reasonCodes()
        );
    }

    public ScoredTransactionDetailResponse toDetailResponse(
            ScoredTransaction transaction,
            EngineIntelligenceResponse engineIntelligence
    ) {
        return new ScoredTransactionDetailResponse(
                transaction.transactionId(),
                transaction.customerId(),
                transaction.correlationId(),
                transaction.transactionTimestamp(),
                transaction.scoredAt(),
                alertResponseMapper.toMoneyResponse(transaction.transactionAmount()),
                alertResponseMapper.toMerchantInfoResponse(transaction.merchantInfo()),
                transaction.fraudScore(),
                transaction.riskLevel(),
                transaction.alertRecommended(),
                transaction.reasonCodes(),
                engineIntelligence,
                analystRecommendation(transaction)
        );
    }

    private AnalystRecommendationResult analystRecommendation(ScoredTransaction transaction) {
        return transaction.analystRecommendation() == null
                ? AnalystRecommendationResult.absent()
                : transaction.analystRecommendation();
    }
}
