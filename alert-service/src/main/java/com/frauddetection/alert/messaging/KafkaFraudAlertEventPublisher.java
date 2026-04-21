package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class KafkaFraudAlertEventPublisher implements FraudAlertEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaFraudAlertEventPublisher.class);

    private final KafkaTemplate<String, FraudAlertEvent> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;

    public KafkaFraudAlertEventPublisher(KafkaTemplate<String, FraudAlertEvent> kafkaTemplate, KafkaTopicProperties kafkaTopicProperties) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @Override
    public void publish(FraudAlertEvent event) {
        try {
            ProducerRecord<String, FraudAlertEvent> record = new ProducerRecord<>(kafkaTopicProperties.fraudAlerts(), event.alertId(), event);
            record.headers().add(new RecordHeader("correlationId", event.correlationId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("alertId", event.alertId().getBytes(StandardCharsets.UTF_8)));
            record.headers().add(new RecordHeader("transactionId", event.transactionId().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get();
            log.atInfo()
                    .addKeyValue("alertId", event.alertId())
                    .addKeyValue("transactionId", event.transactionId())
                    .addKeyValue("correlationId", event.correlationId())
                    .addKeyValue("topic", kafkaTopicProperties.fraudAlerts())
                    .log("Published fraud alert event.");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish fraud alert event.", exception);
        }
    }
}
