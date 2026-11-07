package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.EngineIntelligenceResponse;
import com.frauddetection.alert.api.ScoredTransactionDetailResponse;
import com.frauddetection.alert.api.ScoredTransactionResponse;
import com.frauddetection.alert.api.ScoredMerchantInfoResponse;
import com.frauddetection.alert.domain.ScoredTransaction;
import com.frauddetection.alert.domain.ScoringOccurrenceToken;
import com.frauddetection.common.events.model.MerchantInfo;
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
                toScoredMerchantInfoResponse(transaction.merchantInfo()),
                transaction.fraudScore(),
                transaction.riskLevel(),
                transaction.alertRecommended(),
                transaction.reasonCodes(),
                transaction.scoringOccurrenceOwnership() == null
                        ? null
                        : ScoringOccurrenceToken.from(transaction.scoringOccurrenceOwnership())
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
                toScoredMerchantInfoResponse(transaction.merchantInfo()),
                transaction.fraudScore(),
                transaction.riskLevel(),
                transaction.alertRecommended(),
                transaction.reasonCodes(),
                engineIntelligence,
                analystRecommendation(transaction),
                transaction.scoringOccurrenceOwnership() == null
                        ? null
                        : ScoringOccurrenceToken.from(transaction.scoringOccurrenceOwnership())
        );
    }

    private AnalystRecommendationResult analystRecommendation(ScoredTransaction transaction) {
        return transaction.analystRecommendation() == null
                ? AnalystRecommendationResult.absent()
                : transaction.analystRecommendation();
    }

    private ScoredMerchantInfoResponse toScoredMerchantInfoResponse(MerchantInfo merchantInfo) {
        if (merchantInfo == null) {
            return null;
        }
        return new ScoredMerchantInfoResponse(
                merchantInfo.merchantId(),
                merchantInfo.merchantName(),
                merchantInfo.merchantCategoryCode(),
                merchantInfo.merchantCategory(),
                merchantInfo.channel(),
                merchantInfo.acquiringCountryCode()
        );
    }
}
