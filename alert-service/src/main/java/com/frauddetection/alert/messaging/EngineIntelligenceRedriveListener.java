package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.EngineIntelligenceRecoveryProperties;
import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.engineintelligence.EngineIntelligencePendingProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceRecoveryOutcome;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceRecoveryProvenance;
import com.frauddetection.alert.observability.AlertServiceMetrics;
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
public class EngineIntelligenceRedriveListener {

    private final EngineIntelligencePendingProjectionService pendingProjectionService;
    private final AlertServiceMetrics metrics;
    private final EngineIntelligenceRecoveryProperties recoveryProperties;
    private final String sourceTopic;

    public EngineIntelligenceRedriveListener(
            EngineIntelligencePendingProjectionService pendingProjectionService,
            AlertServiceMetrics metrics,
            EngineIntelligenceRecoveryProperties recoveryProperties,
            KafkaTopicProperties topicProperties
    ) {
        this.pendingProjectionService = Objects.requireNonNull(
                pendingProjectionService,
                "pendingProjectionService is required"
        );
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.recoveryProperties = Objects.requireNonNull(recoveryProperties, "recoveryProperties is required");
        this.sourceTopic = Objects.requireNonNull(topicProperties, "topicProperties is required").transactionScored();
    }

    @KafkaListener(
            id = "engineIntelligenceRedriveListener",
            topics = "${app.kafka.engine-intelligence-recovery.redrive-topic}",
            groupId = "${app.kafka.engine-intelligence-recovery.redrive-group-id}",
            containerFactory = "engineIntelligenceRedriveKafkaListenerContainerFactory",
            autoStartup = "${app.kafka.engine-intelligence-recovery.redrive-enabled:false}"
    )
    public void onMessage(ConsumerRecord<String, TransactionScoredEvent> record) {
        metrics.recordDiagnosticRecovery(EngineIntelligenceRecoveryOutcome.REDRIVE_ATTEMPTED);
        try {
            EngineIntelligenceRecoveryProvenance provenance = validatedProvenance(record);
            pendingProjectionService.deferRecovery(record.value(), provenance);
            metrics.recordDiagnosticRecovery(EngineIntelligenceRecoveryOutcome.REDRIVE_ADMITTED);
        } catch (RuntimeException exception) {
            metrics.recordDiagnosticRecovery(EngineIntelligenceRecoveryOutcome.REDRIVE_REJECTED);
            throw exception;
        }
    }

    private EngineIntelligenceRecoveryProvenance validatedProvenance(
            ConsumerRecord<String, TransactionScoredEvent> record
    ) {
        if (record == null
                || !recoveryProperties.redriveTopic().equals(record.topic())
                || record.value() == null
                || record.key() == null
                || !record.key().equals(record.value().transactionId())
                || !sourceTopic.equals(stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                || !recoveryProperties.sourceConsumerGroup().equals(
                stringHeader(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)
        )) {
            throw new EngineIntelligenceRecoveryValidationException();
        }
        int sourcePartition = intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        long sourceOffset = longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        try {
            return new EngineIntelligenceRecoveryProvenance(
                    EngineIntelligenceRecoveryProvenance.CONTRACT_VERSION,
                    recoveryProperties.deadLetterTopic(),
                    sourceTopic,
                    sourcePartition,
                    sourceOffset,
                    recoveryProperties.sourceConsumerGroup()
            );
        } catch (IllegalArgumentException exception) {
            throw new EngineIntelligenceRecoveryValidationException();
        }
    }

    private String stringHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length == 0) {
            throw new EngineIntelligenceRecoveryValidationException();
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private int intHeader(ConsumerRecord<?, ?> record, String name) {
        byte[] value = headerValue(record, name, Integer.BYTES);
        return ByteBuffer.wrap(value).getInt();
    }

    private long longHeader(ConsumerRecord<?, ?> record, String name) {
        byte[] value = headerValue(record, name, Long.BYTES);
        return ByteBuffer.wrap(value).getLong();
    }

    private byte[] headerValue(ConsumerRecord<?, ?> record, String name, int expectedLength) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length != expectedLength) {
            throw new EngineIntelligenceRecoveryValidationException();
        }
        return header.value();
    }
}
