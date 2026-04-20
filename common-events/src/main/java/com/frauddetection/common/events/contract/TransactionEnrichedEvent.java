package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TransactionEnrichedEvent(
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
        Integer recentTransactionCount,
        String recentTransactionCountWindow,
        Money recentAmountSum,
        String recentAmountSumWindow,
        Double transactionVelocityPerMinute,
        Integer merchantFrequency7d,
        Boolean deviceNovelty,
        Boolean countryMismatch,
        Boolean proxyOrVpnDetected,
        List<String> featureFlags,
        Map<String, Object> featureSnapshot
) {
}
