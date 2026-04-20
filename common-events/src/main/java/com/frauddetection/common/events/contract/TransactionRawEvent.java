package com.frauddetection.common.events.contract;

import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;

import java.time.Instant;
import java.util.Map;

public record TransactionRawEvent(
        String eventId,
        String transactionId,
        String correlationId,
        String customerId,
        String accountId,
        String paymentInstrumentId,
        Instant createdAt,
        Instant transactionTimestamp,
        Money transactionAmount,
        MerchantInfo merchantInfo,
        DeviceInfo deviceInfo,
        LocationInfo locationInfo,
        CustomerContext customerContext,
        String transactionType,
        String authorizationMethod,
        String sourceSystem,
        String traceId,
        Map<String, Object> attributes
) {
}
