package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TransactionScoredEvent(
        String eventId,
        String transactionId,
        String correlationId,
        String customerId,
        String accountId,
        Instant createdAt,
        Instant transactionTimestamp,
        Money transactionAmount,
        MerchantInfo merchantInfo,
        DeviceInfo deviceInfo,
        LocationInfo locationInfo,
        CustomerContext customerContext,
        Double fraudScore,
        RiskLevel riskLevel,
        String scoringStrategy,
        String modelName,
        String modelVersion,
        Instant inferenceTimestamp,
        List<String> reasonCodes,
        Map<String, Object> scoreDetails,
        Map<String, Object> featureSnapshot,
        Boolean alertRecommended
) {
}
