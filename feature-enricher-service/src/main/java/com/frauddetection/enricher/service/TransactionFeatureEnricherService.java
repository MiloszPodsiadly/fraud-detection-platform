package com.frauddetection.enricher.service;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionFeatureEnricherService implements TransactionFeatureEnrichmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransactionFeatureEnricherService.class);

    private final FeatureStore featureStore;
    private final TransactionFeatureCalculator transactionFeatureCalculator;
    private final TransactionEnrichedEventMapper transactionEnrichedEventMapper;
    private final TransactionEnrichedEventPublisher transactionEnrichedEventPublisher;

    public TransactionFeatureEnricherService(
            FeatureStore featureStore,
            TransactionFeatureCalculator transactionFeatureCalculator,
            TransactionEnrichedEventMapper transactionEnrichedEventMapper,
            TransactionEnrichedEventPublisher transactionEnrichedEventPublisher
    ) {
        this.featureStore = featureStore;
        this.transactionFeatureCalculator = transactionFeatureCalculator;
        this.transactionEnrichedEventMapper = transactionEnrichedEventMapper;
        this.transactionEnrichedEventPublisher = transactionEnrichedEventPublisher;
    }

    @Override
    public void enrich(TransactionRawEvent event) {
        if (featureStore.hasRecordedTransaction(event)) {
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("customerId", event.customerId())
                    .addKeyValue("correlationId", event.correlationId())
                    .log("Skipped duplicate raw transaction event.");
            return;
        }

        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("customerId", event.customerId())
                .addKeyValue("correlationId", event.correlationId())
                .log("Started transaction feature enrichment.");

        FeatureStoreSnapshot snapshot = featureStore.loadSnapshot(event);
        EnrichedTransactionFeatures features = transactionFeatureCalculator.calculate(event, snapshot);
        TransactionEnrichedEvent enrichedEvent = transactionEnrichedEventMapper.toEvent(event, features);

        transactionEnrichedEventPublisher.publish(enrichedEvent);
        featureStore.recordTransaction(event);

        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("featureFlags", features.featureFlags())
                .log("Completed transaction feature enrichment.");
    }
}
