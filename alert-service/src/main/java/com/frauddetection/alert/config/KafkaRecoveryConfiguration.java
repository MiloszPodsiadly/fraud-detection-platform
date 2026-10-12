package com.frauddetection.alert.config;

import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionValidationException;
import com.frauddetection.alert.messaging.EngineIntelligenceRecoveryValidationException;
import com.frauddetection.alert.messaging.MlPredictionEvidencePermanentProcessingException;
import com.frauddetection.alert.messaging.ScoringOccurrenceConflictException;
import com.frauddetection.alert.messaging.TransactionScoredRecoveryValidationException;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KafkaRecoveryConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaRecoveryConfiguration.class);

    @Bean
    public ProducerFactory<Object, Object> deadLetterProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        @SuppressWarnings("unchecked")
        Serializer<Object> keySerializer = (Serializer<Object>) (Serializer<?>) new StringSerializer();
        Map<Class<?>, Serializer<?>> valueSerializers = new LinkedHashMap<>();
        valueSerializers.put(byte[].class, new ByteArraySerializer());
        valueSerializers.put(Object.class, new JacksonKafkaSerializer<>());
        return new DefaultKafkaProducerFactory<>(
                properties,
                keySerializer,
                new DelegatingByTypeSerializer(valueSerializers, true)
        );
    }

    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            ProducerFactory<Object, Object> deadLetterProducerFactory
    ) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            KafkaTopicProperties topics
    ) {
        return recoverer(deadLetterKafkaTemplate, (record, exception) ->
                new TopicPartition(topics.transactionsDeadLetter(), record.partition()));
    }

    @Bean
    public DeadLetterPublishingRecoverer mlPredictionEvidenceDeadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            MlPredictionEvidenceRecoveryProperties properties
    ) {
        return recoverer(deadLetterKafkaTemplate, (record, exception) -> new TopicPartition(
                evidenceQuarantineRequired(exception) ? properties.quarantineTopic() : properties.deadLetterTopic(),
                record.partition()
        ));
    }

    @Bean
    public DeadLetterPublishingRecoverer engineIntelligenceDeadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            EngineIntelligenceRecoveryProperties properties
    ) {
        return recoverer(deadLetterKafkaTemplate, (record, exception) ->
                new TopicPartition(properties.deadLetterTopic(), record.partition()));
    }

    @Bean
    public DeadLetterPublishingRecoverer engineIntelligenceRedriveQuarantineRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            EngineIntelligenceRecoveryProperties properties
    ) {
        return recoverer(deadLetterKafkaTemplate, (record, exception) ->
                new TopicPartition(properties.quarantineTopic(), record.partition()));
    }

    @Bean
    public DeadLetterPublishingRecoverer transactionScoredRedriveQuarantineRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            TransactionScoredRecoveryProperties properties
    ) {
        return recoverer(deadLetterKafkaTemplate, (record, exception) ->
                new TopicPartition(properties.quarantineTopic(), record.partition()));
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
            KafkaConsumerProperties properties
    ) {
        DefaultErrorHandler handler = handler(deadLetterPublishingRecoverer, properties);
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                MlPredictionEvidencePermanentProcessingException.class,
                ScoringOccurrenceConflictException.class
        );
        handler.setRetryListeners((ConsumerRecord<?, ?> record, Exception exception, int attempt) ->
                retryLog(record, exception, attempt, "Retrying Kafka record processing before dead-letter handoff."));
        return handler;
    }

    @Bean
    public DefaultErrorHandler mlPredictionEvidenceErrorHandler(
            @Qualifier("mlPredictionEvidenceDeadLetterPublishingRecoverer") DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties properties
    ) {
        DefaultErrorHandler handler = handler(recoverer, properties);
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                MlPredictionEvidencePermanentProcessingException.class
        );
        handler.setRetryListeners((ConsumerRecord<?, ?> record, Exception exception, int attempt) ->
                retryLog(record, exception, attempt, "Retrying ML prediction evidence processing before durable handoff."));
        return handler;
    }

    @Bean
    public DefaultErrorHandler engineIntelligenceErrorHandler(
            @Qualifier("engineIntelligenceDeadLetterPublishingRecoverer") DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties properties
    ) {
        DefaultErrorHandler handler = handler(recoverer, properties);
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                EngineIntelligenceProjectionValidationException.class,
                EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException.class
        );
        return handler;
    }

    @Bean
    public DefaultErrorHandler engineIntelligenceRedriveErrorHandler(
            @Qualifier("engineIntelligenceRedriveQuarantineRecoverer") DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties properties
    ) {
        DefaultErrorHandler handler = handler(recoverer, properties);
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                EngineIntelligenceRecoveryValidationException.class,
                EngineIntelligenceProjectionValidationException.class,
                EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException.class
        );
        return handler;
    }

    @Bean
    public DefaultErrorHandler transactionScoredRedriveErrorHandler(
            @Qualifier("transactionScoredRedriveQuarantineRecoverer") DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties properties
    ) {
        DefaultErrorHandler handler = handler(recoverer, properties);
        handler.addNotRetryableExceptions(
                DeserializationException.class,
                TransactionScoredRecoveryValidationException.class,
                ScoringOccurrenceConflictException.class
        );
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> transactionScoredKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory") ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler,
            KafkaConsumerProperties properties
    ) {
        return listenerFactory(consumerFactory, kafkaErrorHandler, properties, false);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> mlPredictionEvidenceKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory") ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            @Qualifier("mlPredictionEvidenceErrorHandler") DefaultErrorHandler errorHandler,
            KafkaConsumerProperties properties
    ) {
        return listenerFactory(consumerFactory, errorHandler, properties, true);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> engineIntelligenceKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory") ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            @Qualifier("engineIntelligenceErrorHandler") DefaultErrorHandler errorHandler,
            KafkaConsumerProperties properties
    ) {
        return listenerFactory(consumerFactory, errorHandler, properties, true);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> engineIntelligenceRedriveKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory") ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            @Qualifier("engineIntelligenceRedriveErrorHandler") DefaultErrorHandler errorHandler,
            KafkaConsumerProperties properties
    ) {
        return listenerFactory(consumerFactory, errorHandler, properties, true);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> transactionScoredRedriveKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory") ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            @Qualifier("transactionScoredRedriveErrorHandler") DefaultErrorHandler errorHandler,
            KafkaConsumerProperties properties
    ) {
        return listenerFactory(consumerFactory, errorHandler, properties, true);
    }

    private DeadLetterPublishingRecoverer recoverer(
            KafkaOperations<Object, Object> operations,
            java.util.function.BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(operations, destination);
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        recoverer.setLogRecoveryRecord(false);
        return recoverer;
    }

    private DefaultErrorHandler handler(
            DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties properties
    ) {
        long attempts = Math.max((properties.retryAttempts() == null ? 3 : properties.retryAttempts()) - 1L, 0L);
        long backoff = properties.retryBackoffMillis() == null ? 1000L : properties.retryBackoffMillis();
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(backoff, attempts));
        handler.setAckAfterHandle(true);
        return handler;
    }

    private ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> listenerFactory(
            ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            DefaultErrorHandler errorHandler,
            KafkaConsumerProperties properties,
            boolean recordAck
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(properties.concurrency() == null ? 1 : properties.concurrency());
        if (recordAck) {
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        }
        return factory;
    }

    private void retryLog(ConsumerRecord<?, ?> record, Exception exception, int attempt, String message) {
        log.atWarn()
                .addKeyValue("service", "alert-service")
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .addKeyValue("deliveryAttempt", attempt)
                .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                .log(message);
    }

    private boolean evidenceQuarantineRequired(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof DeserializationException
                    || current instanceof MlPredictionEvidencePermanentProcessingException) {
                return true;
            }
        }
        return false;
    }
}
