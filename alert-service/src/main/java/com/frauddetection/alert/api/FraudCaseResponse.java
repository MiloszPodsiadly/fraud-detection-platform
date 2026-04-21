package com.frauddetection.alert.api;

import com.frauddetection.alert.domain.FraudCaseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FraudCaseResponse(
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
}
