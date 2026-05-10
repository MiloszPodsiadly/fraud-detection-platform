package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseStatus;
import com.frauddetection.alert.domain.FraudCasePriority;
import com.frauddetection.common.events.enums.RiskLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FraudCaseResponse(
        String caseId,
        String caseNumber,
        String customerId,
        String suspicionType,
        FraudCaseStatus status,
        FraudCasePriority priority,
        RiskLevel riskLevel,
        List<String> linkedAlertIds,
        String assignedInvestigatorId,
        String createdBy,
        String reason,
        BigDecimal thresholdPln,
        BigDecimal totalAmountPln,
        String aggregationWindow,
        Instant firstTransactionAt,
        Instant lastTransactionAt,
        Instant createdAt,
        Instant updatedAt,
        String analystId,
        String decisionReason,
        List<String> decisionTags,
        Instant decidedAt,
        Instant closedAt,
        String closureReason,
        Long version,
        List<String> transactionIds,
        List<FraudCaseTransactionResponse> transactions
) {
    public FraudCaseResponse(
            String caseId,
            String customerId,
            String suspicionType,
            FraudCaseStatus status,
            String reason,
            BigDecimal thresholdPln,
            BigDecimal totalAmountPln,
            String aggregationWindow,
            Instant firstTransactionAt,
            Instant lastTransactionAt,
            Instant createdAt,
            Instant updatedAt,
            String analystId,
            String decisionReason,
            List<String> decisionTags,
            Instant decidedAt,
            List<String> transactionIds,
            List<FraudCaseTransactionResponse> transactions
    ) {
        this(
                caseId,
                null,
                customerId,
                suspicionType,
                status,
                null,
                null,
                List.of(),
                null,
                null,
                reason,
                thresholdPln,
                totalAmountPln,
                aggregationWindow,
                firstTransactionAt,
                lastTransactionAt,
                createdAt,
                updatedAt,
                analystId,
                decisionReason,
                decisionTags,
                decidedAt,
                null,
                null,
                null,
                transactionIds,
                transactions
        );
    }
}
