package com.frauddetection.alert.api;

import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;

public record AlertSummaryResponse(
        String alertId,
        String transactionId,
        String customerId,
        RiskLevel riskLevel,
        Double fraudScore,
        AlertStatus alertStatus,
        String alertReason,
        Instant alertTimestamp
) {
}
