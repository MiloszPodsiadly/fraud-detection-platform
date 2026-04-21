package com.frauddetection.enricher.messaging;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.enricher.config.KafkaTopicProperties;
import com.frauddetection.enricher.service.TransactionFeatureEnrichmentUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionRawEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionRawEventListener.class);

    private final TransactionFeatureEnrichmentUseCase transactionFeatureEnrichmentUseCase;
    private final KafkaTopicProperties kafkaTopicProperties;

    public TransactionRawEventListener(
            TransactionFeatureEnrichmentUseCase transactionFeatureEnrichmentUseCase,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.transactionFeatureEnrichmentUseCase = transactionFeatureEnrichmentUseCase;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-raw}",
            containerFactory = "transactionRawKafkaListenerContainerFactory"
    )
    public void onMessage(TransactionRawEvent event) {
        log.atInfo()
                .addKeyValue("transactionId", event.transactionId())
                .addKeyValue("correlationId", event.correlationId())
                .addKeyValue("topic", kafkaTopicProperties.transactionRaw())
                .log("Received raw transaction event for enrichment.");
        transactionFeatureEnrichmentUseCase.enrich(event);
    }
}
