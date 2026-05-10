package com.frauddetection.alert.mapper;

import com.frauddetection.alert.api.FraudCaseResponse;
import com.frauddetection.alert.api.FraudCaseSummaryResponse;
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
                document.getCaseNumber(),
                document.getCustomerId(),
                document.getSuspicionType(),
                document.getStatus(),
                document.getPriority(),
                document.getRiskLevel(),
                safeList(document.getLinkedAlertIds()),
                document.getAssignedInvestigatorId(),
                document.getCreatedBy(),
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
                document.getClosedAt(),
                document.getClosureReason(),
                document.getVersion(),
                document.getTransactionIds(),
                document.getTransactions() == null
                        ? java.util.List.of()
                        : document.getTransactions().stream().map(this::toTransactionResponse).toList()
        );
    }

    public FraudCaseSummaryResponse toSummary(FraudCaseDocument document) {
        return new FraudCaseSummaryResponse(
                document.getCaseId(),
                document.getCaseNumber(),
                document.getStatus(),
                document.getPriority(),
                document.getRiskLevel(),
                document.getAssignedInvestigatorId(),
                safeList(document.getLinkedAlertIds()),
                document.getCreatedAt(),
                document.getUpdatedAt()
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

    private java.util.List<String> safeList(java.util.List<String> values) {
        return values == null ? java.util.List.of() : values;
    }
}
