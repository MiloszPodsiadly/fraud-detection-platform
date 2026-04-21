package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.ScoredTransactionResponse;
import com.frauddetection.alert.domain.ScoredTransaction;
import org.springframework.stereotype.Component;

@Component
public class ScoredTransactionResponseMapper {

    public ScoredTransactionResponse toResponse(ScoredTransaction transaction) {
        return new ScoredTransactionResponse(
                transaction.transactionId(),
                transaction.customerId(),
                transaction.correlationId(),
                transaction.transactionTimestamp(),
                transaction.scoredAt(),
                transaction.transactionAmount(),
                transaction.merchantInfo(),
                transaction.fraudScore(),
                transaction.riskLevel(),
                transaction.alertRecommended(),
                transaction.reasonCodes()
        );
    }
}
