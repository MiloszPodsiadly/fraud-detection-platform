package com.frauddetection.alert.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AnalystCaseSummaryResponse(
        String alertId,
        String transactionId,
        String customerId,
        String correlationId,
        TransactionSummary transactionSummary,
        List<FraudReasonSummary> mainFraudReasons,
        CustomerRecentBehaviorSummary customerRecentBehaviorSummary,
        RecommendedNextAction recommendedNextAction,
        Map<String, Object> supportingEvidence,
        Instant generatedAt
) {
}
