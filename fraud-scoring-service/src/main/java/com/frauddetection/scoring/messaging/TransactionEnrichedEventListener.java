package com.frauddetection.scoring.messaging;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.observability.TraceContext;
import com.frauddetection.scoring.config.KafkaTopicProperties;
import com.frauddetection.scoring.service.TransactionFraudScoringUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class TransactionEnrichedEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionEnrichedEventListener.class);

    private final TransactionFraudScoringUseCase transactionFraudScoringUseCase;
    private final KafkaTopicProperties kafkaTopicProperties;

    public TransactionEnrichedEventListener(
            TransactionFraudScoringUseCase transactionFraudScoringUseCase,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.transactionFraudScoringUseCase = transactionFraudScoringUseCase;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transaction-enriched}",
            containerFactory = "transactionEnrichedKafkaListenerContainerFactory"
    )
    public void onMessage(
            TransactionEnrichedEvent event,
            @Header(name = TraceContext.KAFKA_TRACE_ID_HEADER, required = false) String traceId
    ) {
        try (TraceContext.Scope ignored = TraceContext.open(event.correlationId(), traceId, event.transactionId(), null)) {
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", kafkaTopicProperties.transactionEnriched())
                    .log("Received enriched transaction event for scoring.");
            transactionFraudScoringUseCase.score(event);
        }
    }
}
