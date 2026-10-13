package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.config.TransactionScoredRecoveryProperties;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TransactionScoredRedriveListenerTest {

    private static final String SOURCE_TOPIC = "transactions.scored";
    private static final String SOURCE_GROUP = "alert-service";
    private static final String REDRIVE_TOPIC = "transactions.redrive";

    private final TransactionScoredEventListener sourceListener = mock(TransactionScoredEventListener.class);
    private final TransactionScoredRedriveListener listener = new TransactionScoredRedriveListener(
            sourceListener,
            new TransactionScoredRecoveryProperties(
                    REDRIVE_TOPIC,
                    "transactions.quarantine",
                    "alert-service-transaction-scored-redrive",
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
    void delegatesVerifiedRedriveThroughTheAuthoritativeSourceListener() {
        TransactionScoredEvent event = TransactionFixtures.scoredTransaction().build();

        listener.onMessage(record(event, SOURCE_GROUP));

        verify(sourceListener).onMessage(event, null);
    }

    @Test
    void rejectsRedriveWithoutCompleteOriginalCoordinates() {
        TransactionScoredEvent event = TransactionFixtures.scoredTransaction().build();
        ConsumerRecord<String, TransactionScoredEvent> record = record(event, SOURCE_GROUP);
        record.headers().remove(KafkaHeaders.DLT_ORIGINAL_OFFSET);

        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(TransactionScoredRecoveryValidationException.class);
        verify(sourceListener, never()).onMessage(event, null);
    }

    @Test
    void rejectsWrongSourceGroupTopicKeyAndDedicatedRedriveTopic() {
        TransactionScoredEvent event = TransactionFixtures.scoredTransaction().build();

        assertRejected(record(event, "other-group"), event);

        ConsumerRecord<String, TransactionScoredEvent> wrongKey = record(event, SOURCE_GROUP);
        ConsumerRecord<String, TransactionScoredEvent> rekeyed = new ConsumerRecord<>(
                REDRIVE_TOPIC,
                0,
                0L,
                "other-transaction",
                event
        );
        wrongKey.headers().forEach(header -> rekeyed.headers().add(header));
        assertRejected(rekeyed, event);

        ConsumerRecord<String, TransactionScoredEvent> wrongTopic = new ConsumerRecord<>(
                "user-controlled-topic",
                0,
                0L,
                event.transactionId(),
                event
        );
        record(event, SOURCE_GROUP).headers().forEach(header -> wrongTopic.headers().add(header));
        assertRejected(wrongTopic, event);
    }

    @Test
    void rejectsRecoveryConfigurationThatOverlapsTheSourceTopic() {
        assertThatThrownBy(() -> new TransactionScoredRedriveListener(
                sourceListener,
                new TransactionScoredRecoveryProperties(
                        SOURCE_TOPIC,
                        "transactions.quarantine",
                        "alert-service-transaction-scored-redrive",
                        SOURCE_GROUP,
                        false
                ),
                new KafkaTopicProperties(
                        SOURCE_TOPIC,
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TRANSACTION_SCORED_RECOVERY_TOPICS_MUST_BE_DISTINCT");
    }

    private void assertRejected(
            ConsumerRecord<String, TransactionScoredEvent> record,
            TransactionScoredEvent event
    ) {
        assertThatThrownBy(() -> listener.onMessage(record))
                .isInstanceOf(TransactionScoredRecoveryValidationException.class);
        verify(sourceListener, never()).onMessage(event, null);
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
}
