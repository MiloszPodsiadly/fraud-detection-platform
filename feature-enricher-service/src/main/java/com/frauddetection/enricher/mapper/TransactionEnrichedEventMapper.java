package com.frauddetection.enricher.mapper;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TransactionEnrichedEventMapper {

    public TransactionEnrichedEvent toEvent(TransactionRawEvent event, EnrichedTransactionFeatures features) {
        return new TransactionEnrichedEvent(
                UUID.randomUUID().toString(),
                event.transactionId(),
                event.correlationId(),
                event.customerId(),
                event.accountId(),
                Instant.now(),
                event.transactionTimestamp(),
                event.transactionAmount(),
                event.merchantInfo(),
                event.deviceInfo(),
                event.locationInfo(),
                event.customerContext(),
                features.recentTransactionCount(),
                features.recentTransactionCountWindow(),
                features.recentAmountSum(),
                features.recentAmountSumWindow(),
                features.transactionVelocityPerMinute(),
                features.merchantFrequency7d(),
                features.deviceNovelty(),
                features.countryMismatch(),
                features.proxyOrVpnDetected(),
                features.featureFlags(),
                features.featureSnapshot()
        );
    }
}
