package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MlPredictionEvidenceRedriveListenerTest {

    private final MlPredictionEvidenceEventListener evidenceListener = mock(MlPredictionEvidenceEventListener.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MlPredictionEvidenceRedriveListener listener = new MlPredictionEvidenceRedriveListener(
            evidenceListener,
            new AlertServiceMetrics(meterRegistry),
            new KafkaTopicProperties(
                    "transactions.scored",
                    "fraud.alerts",
                    "fraud.decisions",
                    "transactions.dead-letter"
            )
    );

    @Test
    void delegatesProvenRedriveOnlyToCanonicalEvidenceListener() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);

        assertThatCode(() -> listener.onMessage(record(event, completeProvenance())))
                .doesNotThrowAnyException();

        verify(evidenceListener).onMessage(event);
        assertThat(meterRegistry.get("ml_prediction_evidence_recovery_total")
                .tag("stage", "REDRIVE")
                .tag("outcome", "ATTEMPTED")
                .counter()
                .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("ml_prediction_evidence_recovery_total")
                .tag("stage", "REDRIVE")
                .tag("outcome", "ACCEPTED")
                .counter()
                .count()).isEqualTo(1.0d);
    }

    @Test
    void rejectsRedriveWithoutOriginalSourceCoordinates() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);

        assertThatThrownBy(() -> listener.onMessage(record(event, new RecordHeaders())))
                .isInstanceOf(MlPredictionEvidencePermanentProcessingException.class)
                .hasMessage("ML_PREDICTION_EVIDENCE_PROJECTION_INVALID_EVIDENCE");
    }

    @Test
    void rejectsRedriveFromNonCanonicalSourceTopic() {
        TransactionScoredEvent event = mock(TransactionScoredEvent.class);
        RecordHeaders headers = completeProvenance();
        headers.remove(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        headers.add(new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC,
                "untrusted.scored-events".getBytes(StandardCharsets.UTF_8)
        ));

        assertThatThrownBy(() -> listener.onMessage(record(event, headers)))
                .isInstanceOf(MlPredictionEvidencePermanentProcessingException.class)
                .hasMessage("ML_PREDICTION_EVIDENCE_PROJECTION_INVALID_EVIDENCE");
    }

    private ConsumerRecord<String, TransactionScoredEvent> record(
            TransactionScoredEvent event,
            RecordHeaders headers
    ) {
        return new ConsumerRecord<>(
                "ml.prediction-evidence.redrive",
                0,
                1L,
                0L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                0,
                0,
                "transaction-1",
                event,
                headers,
                java.util.Optional.empty()
        );
    }

    private RecordHeaders completeProvenance() {
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_TOPIC,
                "transactions.scored".getBytes(StandardCharsets.UTF_8)
        ));
        headers.add(new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_PARTITION,
                ByteBuffer.allocate(Integer.BYTES).putInt(0).array()
        ));
        headers.add(new RecordHeader(
                KafkaHeaders.DLT_ORIGINAL_OFFSET,
                ByteBuffer.allocate(Long.BYTES).putLong(42L).array()
        ));
        return headers;
    }
}
