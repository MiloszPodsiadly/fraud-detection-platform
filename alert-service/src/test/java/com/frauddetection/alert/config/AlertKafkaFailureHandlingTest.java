package com.frauddetection.alert.config;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
