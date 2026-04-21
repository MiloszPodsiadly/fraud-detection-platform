package com.frauddetection.ingest.messaging;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.exception.KafkaPublishException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class KafkaTransactionRawEventProducer implements TransactionRawEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransactionRawEventProducer.class);

    private final KafkaTemplate<String, TransactionRawEvent> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;

    public KafkaTransactionRawEventProducer(
            KafkaTemplate<String, TransactionRawEvent> kafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @Override
    public void publish(TransactionRawEvent event) {
        String topic = kafkaTopicProperties.transactionRaw();
        try {
            ProducerRecord<String, TransactionRawEvent> record = new ProducerRecord<>(topic, event.transactionId(), event);
            record.headers().add(new RecordHeader("correlationId", event.correlationId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("traceId", event.traceId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("transactionId", event.transactionId().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get();
            log.atInfo()
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", topic)
                    .log("Published raw transaction event.");
        } catch (Exception exception) {
            throw new KafkaPublishException("Failed to publish transaction event.", exception);
        }
    }
}
