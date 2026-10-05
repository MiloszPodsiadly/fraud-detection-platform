package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.config.EngineIntelligenceRecoveryProperties;
import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.messaging.EngineIntelligenceRecoveryValidationException;
import com.frauddetection.alert.messaging.EngineIntelligenceRedriveListener;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EngineIntelligenceRedriveListenerTest {

    private static final String SOURCE_TOPIC = "transactions.scored";
    private static final String SOURCE_GROUP = "alert-service-engine-intelligence";
    private static final String REDRIVE_TOPIC = "engine-intelligence.redrive";

    private final EngineIntelligencePendingProjectionService pendingService =
            mock(EngineIntelligencePendingProjectionService.class);
    private final EngineIntelligenceRedriveListener listener = new EngineIntelligenceRedriveListener(
            pendingService,
            mock(AlertServiceMetrics.class),
            new EngineIntelligenceRecoveryProperties(
                    "engine-intelligence.dead-letter",
                    REDRIVE_TOPIC,
                    "engine-intelligence.quarantine",
                    "alert-service-engine-intelligence-redrive",
                    SOURCE_GROUP,
                    false
            ),
            new KafkaTopicProperties(
                    SOURCE_TOPIC,
                    "fraud.alerts",
                    "fraud.decisions",
                    "transactions.dead-letter"
            )
    );

    @Test
    void admitsOnlyVerifiedProjectionRecoveryToDurableInbox() {
        TransactionScoredEvent event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );
        ConsumerRecord<String, TransactionScoredEvent> record = record(event, SOURCE_GROUP);

        listener.onMessage(record);

        verify(pendingService).deferRecovery(
                eq(event),
                eq(new EngineIntelligenceRecoveryProvenance(
                        EngineIntelligenceRecoveryProvenance.CONTRACT_VERSION,
                        "engine-intelligence.dead-letter",
                        SOURCE_TOPIC,
                        1,
                        42L,
                        SOURCE_GROUP
                ))
        );
    }

    @Test
    void rejectsMissingOriginalCoordinates() {
        TransactionScoredEvent event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );
        ConsumerRecord<String, TransactionScoredEvent> record = record(event, SOURCE_GROUP);
        record.headers().remove(KafkaHeaders.DLT_ORIGINAL_OFFSET);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(EngineIntelligenceRecoveryValidationException.class);
        verify(pendingService, never()).deferRecovery(eq(event), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void originalGroupHeaderIsConsistencyEvidenceNotAReplacementForDedicatedTopic() {
        TransactionScoredEvent event = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );
        ConsumerRecord<String, TransactionScoredEvent> wrongTopic = new ConsumerRecord<>(
                "user-controlled-topic",
                0,
                0L,
                event.transactionId(),
                event
        );
        copyHeaders(record(event, SOURCE_GROUP), wrongTopic);

        assertThatThrownBy(() -> listener.onMessage(wrongTopic))
                .isInstanceOf(EngineIntelligenceRecoveryValidationException.class);
        verify(pendingService, never()).deferRecovery(eq(event), org.mockito.ArgumentMatchers.any());
    }

    private ConsumerRecord<String, TransactionScoredEvent> record(
            TransactionScoredEvent event,
            String originalGroup
    ) {
        ConsumerRecord<String, TransactionScoredEvent> record = new ConsumerRecord<>(
                REDRIVE_TOPIC,
                0,
                0L,
                event.transactionId(),
                event
        );
        RecordHeaders headers = (RecordHeaders) record.headers();
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, SOURCE_TOPIC.getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(1).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(42L).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, originalGroup.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void copyHeaders(
            ConsumerRecord<String, TransactionScoredEvent> source,
            ConsumerRecord<String, TransactionScoredEvent> target
    ) {
        source.headers().forEach(header -> target.headers().add(header));
    }
}
