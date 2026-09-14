package com.frauddetection.common.events.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueDeserializer;
import com.frauddetection.common.events.features.FeatureSnapshotWireValueNormalizer;
import com.frauddetection.common.events.model.CustomerContext;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.model.LocationInfo;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.model.Money;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
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
        @JsonDeserialize(using = FeatureSnapshotWireValueDeserializer.class)
        Map<String, Object> featureSnapshot
) {
    public TransactionEnrichedEvent {
        if (featureSnapshot != null) {
            featureSnapshot = FeatureSnapshotWireValueNormalizer.normalize(featureSnapshot);
        }
    }
}
