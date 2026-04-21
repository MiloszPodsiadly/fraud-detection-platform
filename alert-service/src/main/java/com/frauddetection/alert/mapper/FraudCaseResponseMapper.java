package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.FraudCaseTransactionResponse;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.FraudCaseTransactionDocument;
import org.springframework.stereotype.Component;

@Component
public class FraudCaseResponseMapper {

    private final AlertResponseMapper alertResponseMapper;

    public FraudCaseResponseMapper(AlertResponseMapper alertResponseMapper) {
        this.alertResponseMapper = alertResponseMapper;
    }

    public FraudCaseResponse toResponse(FraudCaseDocument document) {
        return new FraudCaseResponse(
                document.getCaseId(),
                document.getCustomerId(),
                document.getSuspicionType(),
                document.getStatus(),
                document.getReason(),
                document.getThresholdPln(),
                document.getTotalAmountPln(),
                document.getAggregationWindow(),
                document.getFirstTransactionAt(),
                document.getLastTransactionAt(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getAnalystId(),
                document.getDecisionReason(),
                document.getDecisionTags(),
                document.getDecidedAt(),
                document.getTransactionIds(),
                document.getTransactions() == null
                        ? java.util.List.of()
                        : document.getTransactions().stream().map(this::toTransactionResponse).toList()
        );
    }

    private FraudCaseTransactionResponse toTransactionResponse(FraudCaseTransactionDocument transaction) {
        return new FraudCaseTransactionResponse(
                transaction.getTransactionId(),
                transaction.getCorrelationId(),
                transaction.getTransactionTimestamp(),
                alertResponseMapper.toMoneyResponse(transaction.getTransactionAmount()),
                transaction.getAmountPln(),
                transaction.getFraudScore(),
                transaction.getRiskLevel()
        );
    }
}
