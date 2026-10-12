package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.config.TransactionScoredRecoveryProperties;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class TransactionScoredRedriveListener {

    private final TransactionScoredEventListener sourceListener;
    private final TransactionScoredRecoveryProperties recoveryProperties;
    private final String sourceTopic;

    public TransactionScoredRedriveListener(
            TransactionScoredEventListener sourceListener,
            TransactionScoredRecoveryProperties recoveryProperties,
            KafkaTopicProperties topicProperties
    ) {
        this.sourceListener = Objects.requireNonNull(sourceListener, "sourceListener is required");
        this.recoveryProperties = Objects.requireNonNull(recoveryProperties, "recoveryProperties is required");
        this.sourceTopic = Objects.requireNonNull(topicProperties, "topicProperties is required").transactionScored();
        if (sourceTopic.equals(recoveryProperties.redriveTopic())
                || sourceTopic.equals(recoveryProperties.quarantineTopic())
                || recoveryProperties.redriveTopic().equals(recoveryProperties.quarantineTopic())) {
            throw new IllegalArgumentException("TRANSACTION_SCORED_RECOVERY_TOPICS_MUST_BE_DISTINCT");
        }
    }

    @KafkaListener(
            id = "transactionScoredRedriveListener",
            topics = "${app.kafka.transaction-scored-recovery.redrive-topic}",
            groupId = "${app.kafka.transaction-scored-recovery.redrive-group-id}",
            containerFactory = "transactionScoredRedriveKafkaListenerContainerFactory",
            autoStartup = "${app.kafka.transaction-scored-recovery.redrive-enabled:false}"
    )
    public void onMessage(ConsumerRecord<String, TransactionScoredEvent> record) {
        validateProvenance(record);
        sourceListener.onMessage(record.value(), null);
    }

    private void validateProvenance(ConsumerRecord<String, TransactionScoredEvent> record) {
        if (record == null
                || !recoveryProperties.redriveTopic().equals(record.topic())
                || record.value() == null
                || record.key() == null
                || !record.key().equals(record.value().transactionId())
                || !sourceTopic.equals(stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                || !recoveryProperties.sourceConsumerGroup().equals(
                stringHeader(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)
        )
                || intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION) < 0
                || longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET) < 0L) {
            throw new TransactionScoredRecoveryValidationException();
        }
    }

    private String stringHeader(ConsumerRecord<?, ?> record, String name) {
        byte[] value = headerValue(record, name, -1);
        return new String(value, StandardCharsets.UTF_8);
    }

    private int intHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(headerValue(record, name, Integer.BYTES)).getInt();
    }

    private long longHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(headerValue(record, name, Long.BYTES)).getLong();
    }

    private byte[] headerValue(ConsumerRecord<?, ?> record, String name, int expectedLength) {
        Header header = record.headers().lastHeader(name);
        if (header == null
                || header.value() == null
                || header.value().length == 0
                || (expectedLength >= 0 && header.value().length != expectedLength)) {
            throw new TransactionScoredRecoveryValidationException();
        }
        return header.value();
    }
}
