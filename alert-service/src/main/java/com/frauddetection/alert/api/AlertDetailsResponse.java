package com.frauddetection.alert.api;

import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AlertDetailsResponse(
        String alertId,
        String transactionId,
        String customerId,
        String correlationId,
        Instant createdAt,
        Instant alertTimestamp,
        RiskLevel riskLevel,
        Double fraudScore,
        AlertStatus alertStatus,
        String alertReason,
        List<String> reasonCodes,
        MoneyResponse transactionAmount,
        MerchantInfoResponse merchantInfo,
        DeviceInfoResponse deviceInfo,
        LocationInfoResponse locationInfo,
        CustomerContextResponse customerContext,
        Map<String, Object> scoreDetails,
        Map<String, Object> featureSnapshot,
        AnalystDecision analystDecision,
        String analystId,
        String decisionReason,
        List<String> decisionTags,
        Instant decidedAt
) {
}
