package com.frauddetection.scoring.messaging;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.observability.TraceContext;
import com.frauddetection.scoring.config.KafkaTopicProperties;
import com.frauddetection.scoring.exception.FraudScoringException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class KafkaTransactionScoredEventPublisher implements TransactionScoredEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransactionScoredEventPublisher.class);

    private final KafkaTemplate<String, TransactionScoredEvent> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;

    public KafkaTransactionScoredEventPublisher(
            KafkaTemplate<String, TransactionScoredEvent> kafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @Override
    public void publish(TransactionScoredEvent event) {
        String topic = kafkaTopicProperties.transactionScored();
        try {
            ProducerRecord<String, TransactionScoredEvent> record = new ProducerRecord<>(topic, event.transactionId(), event);
            record.headers().add(new RecordHeader(TraceContext.KAFKA_CORRELATION_ID_HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader(TraceContext.KAFKA_TRANSACTION_ID_HEADER, event.transactionId().getBytes(StandardCharsets.UTF_8)));
            addTraceHeaderIfPresent(record, TraceContext.currentTraceId());
            kafkaTemplate.send(record).get();
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", topic)
                    .log("Published scored transaction event.");
        } catch (Exception exception) {
            throw new FraudScoringException("Failed to publish scored transaction event.", exception);
        }
    }

    private void addTraceHeaderIfPresent(ProducerRecord<String, TransactionScoredEvent> record, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        record.headers().add(new RecordHeader(TraceContext.KAFKA_TRACE_ID_HEADER, traceId.getBytes(StandardCharsets.UTF_8)));
    }
}
