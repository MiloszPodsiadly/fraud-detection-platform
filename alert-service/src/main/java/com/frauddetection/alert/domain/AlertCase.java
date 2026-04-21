package com.frauddetection.alert.domain;

import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AlertCase(
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
        Money transactionAmount,
        MerchantInfo merchantInfo,
        DeviceInfo deviceInfo,
        LocationInfo locationInfo,
        CustomerContext customerContext,
        Map<String, Object> scoreDetails,
        Map<String, Object> featureSnapshot,
        AnalystDecision analystDecision,
        String analystId,
        String decisionReason,
        List<String> decisionTags,
        Instant decidedAt
) {
}
