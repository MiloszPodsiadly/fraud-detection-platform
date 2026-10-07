package com.frauddetection.alert.config;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.observability.TraceContext;
import com.frauddetection.alert.engineintelligence.MlPredictionEvidenceProjectionReason;
import com.frauddetection.alert.messaging.MlPredictionEvidencePermanentProcessingException;
import com.frauddetection.alert.messaging.MlPredictionEvidenceTransientProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertKafkaFailureHandlingTest {

    private final AlertKafkaConfig config = new AlertKafkaConfig();

    @Test
    void capturesMalformedPayloadForContainerErrorHandling() {
        DefaultKafkaConsumerFactory<String, TransactionScoredEvent> consumerFactory =
                (DefaultKafkaConsumerFactory<String, TransactionScoredEvent>) config
                        .transactionScoredEventConsumerFactory(new KafkaProperties());

        assertThat(consumerFactory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);

        RecordHeaders headers = new RecordHeaders();
        byte[] malformedPayload = "{not-json".getBytes(StandardCharsets.UTF_8);

        TransactionScoredEvent event = consumerFactory.getValueDeserializer()
                .deserialize("transactions.scored", headers, malformedPayload);

        assertThat(event).isNull();
        assertThat(headers).isNotEmpty();
    }

    @Test
    void canonicalConsumerFactoryRejectsInvalidCanonicalEvidence() {
        byte[] payload = ("{"
                + "\"eventId\":\"event-1\","
                + "\"transactionId\":\"transaction-1\","
                + "\"correlationId\":\"correlation-1\","
                + "\"riskLevel\":\"HIGH\","
                + "\"mlPredictionEvidence\":{\"contractVersion\":2}"
                + "}").getBytes(StandardCharsets.UTF_8);
        DefaultKafkaConsumerFactory<String, TransactionScoredEvent> canonicalFactory =
                (DefaultKafkaConsumerFactory<String, TransactionScoredEvent>) config
                        .transactionScoredEventConsumerFactory(new KafkaProperties());
        RecordHeaders headers = new RecordHeaders();

        TransactionScoredEvent rejected = canonicalFactory.getValueDeserializer()
                .deserialize("transactions.scored", headers, payload);

        assertThat(rejected).isNull();
        assertThat(headers).isNotEmpty();
    }

    @Test
    void everyTransactionScoredContainerUsesCanonicalConsumerFactoryBean() {
        List<String> containerFactoryMethods = List.of(
                "transactionScoredKafkaListenerContainerFactory",
                "mlPredictionEvidenceKafkaListenerContainerFactory",
                "engineIntelligenceKafkaListenerContainerFactory",
                "engineIntelligenceRedriveKafkaListenerContainerFactory"
        );

        assertThat(containerFactoryMethods).allSatisfy(methodName -> {
            var method = Arrays.stream(AlertKafkaConfig.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            Qualifier qualifier = method.getParameters()[0].getAnnotation(Qualifier.class);

            assertThat(qualifier).isNotNull();
            assertThat(qualifier.value()).isEqualTo("transactionScoredEventConsumerFactory");
        });

        assertThat(Arrays.stream(AlertKafkaConfig.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("mlPredictionEvidenceConsumerFactory");
    }

    @Test
    void preservesOriginalMalformedBytesForDeadLetterPublication() {
        DefaultKafkaProducerFactory<Object, Object> producerFactory =
                (DefaultKafkaProducerFactory<Object, Object>) config.deadLetterProducerFactory(new KafkaProperties());
        Serializer<Object> serializer = producerFactory.getValueSerializer();
        byte[] malformedPayload = "{not-json".getBytes(StandardCharsets.UTF_8);

        assertThat(serializer.serialize("transactions.dead-letter", malformedPayload))
                .containsExactly(malformedPayload);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterQuarantinePreservesOriginalBytesAndSafeSourceMetadata() {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(
                kafkaOperations,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );
        byte[] invalidEvidence = "{\"mlPredictionEvidence\":{\"contractVersion\":2}}"
                .getBytes(StandardCharsets.UTF_8);
        RecordHeaders sourceHeaders = new RecordHeaders();
        sourceHeaders.add(new RecordHeader(
                TraceContext.KAFKA_CORRELATION_ID_HEADER,
                "correlation-safe-1".getBytes(StandardCharsets.UTF_8)
        ));
        ConsumerRecord<Object, Object> source = new ConsumerRecord<>(
                "transactions.scored",
                1,
                42L,
                0L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                0,
                0,
                "transaction-1",
                invalidEvidence,
                sourceHeaders,
                java.util.Optional.empty()
        );

        recoverer.accept(source, null, new DeserializationException(
                "invalid canonical event",
                invalidEvidence,
                false,
                new IllegalArgumentException("bounded evidence rejection")
        ));

        ArgumentCaptor<ProducerRecord<Object, Object>> publishedRecord = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations).send(publishedRecord.capture());
        ProducerRecord<Object, Object> quarantine = publishedRecord.getValue();
        assertThat(quarantine.topic()).isEqualTo("transactions.dead-letter");
        assertThat(quarantine.partition()).isEqualTo(1);
        assertThat((byte[]) quarantine.value()).containsExactly(invalidEvidence);
        assertThat(quarantine.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNotNull();
        assertThat(quarantine.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
        assertThat(quarantine.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
        assertThat(quarantine.headers().lastHeader(TraceContext.KAFKA_CORRELATION_ID_HEADER).value())
                .containsExactly("correlation-safe-1".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedDeadLetterPublicationIsPropagated() {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(
                kafkaOperations,
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );

        byte[] malformedPayload = "{not-json".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(
                "transactions.scored",
                0,
                42L,
                "key",
                malformedPayload
        );

        assertThatThrownBy(() -> recoverer.accept(
                record,
                null,
                new DeserializationException(
                        "malformed transaction-scored event",
                        malformedPayload,
                        false,
                        new IllegalArgumentException("invalid payload")
                )
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void acknowledgesOnlyAfterConfiguredRecoveryCompletes() {
        DeadLetterPublishingRecoverer recoverer = config.deadLetterPublishingRecoverer(
                mock(KafkaOperations.class),
                new KafkaTopicProperties(
                        "transactions.scored",
                        "fraud.alerts",
                        "fraud.decisions",
                        "transactions.dead-letter"
                )
        );
        DefaultErrorHandler errorHandler = config.kafkaErrorHandler(
                recoverer,
                new KafkaConsumerProperties(1, 3, 1000L)
        );

        assertThat(errorHandler.isAckAfterHandle()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientEvidenceFailureUsesDedicatedRecoverableDeadLetterTopic() {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.mlPredictionEvidenceDeadLetterPublishingRecoverer(
                kafkaOperations,
                evidenceRecoveryProperties()
        );
        ConsumerRecord<Object, Object> source = new ConsumerRecord<>(
                "transactions.scored",
                2,
                41L,
                "transaction-1",
                mock(TransactionScoredEvent.class)
        );

        recoverer.accept(
                source,
                null,
                new MlPredictionEvidenceTransientProcessingException(
                        MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE
                )
        );

        ArgumentCaptor<ProducerRecord<Object, Object>> published = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations).send(published.capture());
        assertThat(published.getValue().topic()).isEqualTo("ml.prediction-evidence.dead-letter");
        assertThat(published.getValue().headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)).isNotNull();
        assertThat(published.getValue().headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
        assertThat(published.getValue().headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void permanentEvidenceFailureUsesTerminalQuarantineTopic() {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.mlPredictionEvidenceDeadLetterPublishingRecoverer(
                kafkaOperations,
                evidenceRecoveryProperties()
        );
        ConsumerRecord<Object, Object> source = new ConsumerRecord<>(
                "ml.prediction-evidence.redrive",
                0,
                7L,
                "transaction-1",
                mock(TransactionScoredEvent.class)
        );

        recoverer.accept(
                source,
                null,
                new MlPredictionEvidencePermanentProcessingException(
                        MlPredictionEvidenceProjectionReason.REPLAY_CONFLICT
                )
        );

        ArgumentCaptor<ProducerRecord<Object, Object>> published = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations).send(published.capture());
        assertThat(published.getValue().topic()).isEqualTo("ml.prediction-evidence.quarantine");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedEvidenceDeadLetterPublicationIsPropagated() {
        KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        DeadLetterPublishingRecoverer recoverer = config.mlPredictionEvidenceDeadLetterPublishingRecoverer(
                kafkaOperations,
                evidenceRecoveryProperties()
        );
        ConsumerRecord<Object, Object> source = new ConsumerRecord<>(
                "transactions.scored",
                0,
                42L,
                "transaction-1",
                mock(TransactionScoredEvent.class)
        );

        assertThatThrownBy(() -> recoverer.accept(
                source,
                null,
                new MlPredictionEvidenceTransientProcessingException(
                        MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE
                )
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void evidenceConsumerUsesBoundedConcurrencyAndRecordAcknowledgement() {
        var factory = config.mlPredictionEvidenceKafkaListenerContainerFactory(
                mock(ConsumerFactory.class),
                mock(DefaultErrorHandler.class),
                new KafkaConsumerProperties(1, 3, 1000L)
        );

        var container = (ConcurrentMessageListenerContainer<String, TransactionScoredEvent>)
                factory.createContainer("transactions.scored");
        assertThat(container.getConcurrency()).isEqualTo(1);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void engineIntelligenceConsumerHasIndependentRecordAcknowledgementFactory() {
        var factory = config.engineIntelligenceKafkaListenerContainerFactory(
                mock(ConsumerFactory.class),
                mock(DefaultErrorHandler.class),
                new KafkaConsumerProperties(1, 3, 1000L)
        );

        var container = (ConcurrentMessageListenerContainer<String, TransactionScoredEvent>)
                factory.createContainer("transactions.scored");
        assertThat(container.getConcurrency()).isEqualTo(1);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    private MlPredictionEvidenceRecoveryProperties evidenceRecoveryProperties() {
        return new MlPredictionEvidenceRecoveryProperties(
                "ml.prediction-evidence.dead-letter",
                "ml.prediction-evidence.redrive",
                "ml.prediction-evidence.quarantine",
                "alert-service-ml-prediction-evidence-redrive",
                false
        );
    }
}
