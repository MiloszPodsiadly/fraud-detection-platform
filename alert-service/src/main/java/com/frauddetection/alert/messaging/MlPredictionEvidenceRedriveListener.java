package com.frauddetection.alert.messaging;

import com.frauddetection.alert.config.KafkaTopicProperties;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionReason;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class MlPredictionEvidenceRedriveListener {

    private final MlPredictionEvidenceEventListener evidenceListener;
    private final AlertServiceMetrics metrics;
    private final String sourceTopic;

    public MlPredictionEvidenceRedriveListener(
            MlPredictionEvidenceEventListener evidenceListener,
            AlertServiceMetrics metrics,
            KafkaTopicProperties topicProperties
    ) {
        this.evidenceListener = Objects.requireNonNull(evidenceListener, "evidenceListener is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
        this.sourceTopic = Objects.requireNonNull(topicProperties, "topicProperties is required").transactionScored();
    }

    @KafkaListener(
            id = "mlPredictionEvidenceRedriveListener",
            topics = "${app.kafka.evidence-recovery.redrive-topic}",
            groupId = "${app.kafka.evidence-recovery.redrive-group-id}",
            containerFactory = "mlPredictionEvidenceKafkaListenerContainerFactory",
            autoStartup = "${app.kafka.evidence-recovery.redrive-enabled:false}"
    )
    public void onMessage(ConsumerRecord<String, TransactionScoredEvent> record) {
        requireOriginalSourceProvenance(record);
        metrics.recordMlPredictionEvidenceRecovery("REDRIVE", "ATTEMPTED");
        try {
            evidenceListener.onMessage(record.value());
            metrics.recordMlPredictionEvidenceRecovery("REDRIVE", "ACCEPTED");
        } catch (MlPredictionEvidenceTransientProcessingException exception) {
            metrics.recordMlPredictionEvidenceRecovery("REDRIVE", "TRANSIENT_FAILURE");
            throw exception;
        } catch (MlPredictionEvidencePermanentProcessingException exception) {
            metrics.recordMlPredictionEvidenceRecovery("REDRIVE", "PERMANENT_FAILURE");
            throw exception;
        }
    }

    private void requireOriginalSourceProvenance(ConsumerRecord<?, ?> record) {
        if (record == null
                || record.value() == null
                || missing(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)
                || missing(record, KafkaHeaders.DLT_ORIGINAL_PARTITION)
                || missing(record, KafkaHeaders.DLT_ORIGINAL_OFFSET)
                || !sourceTopic.equals(originalTopic(record))) {
            throw new MlPredictionEvidencePermanentProcessingException(
                    MlPredictionEvidenceProjectionReason.INVALID_EVIDENCE
            );
        }
    }

    private String originalTopic(ConsumerRecord<?, ?> record) {
        return new String(
                record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                StandardCharsets.UTF_8
        );
    }

    private boolean missing(ConsumerRecord<?, ?> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        return header == null || header.value() == null || header.value().length == 0;
    }
}
