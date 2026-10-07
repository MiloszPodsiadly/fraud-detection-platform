package com.frauddetection.alert.config;

import com.frauddetection.alert.messaging.EngineIntelligenceRecoveryValidationException;
import com.frauddetection.alert.messaging.MlPredictionEvidencePermanentProcessingException;
import com.frauddetection.alert.messaging.ScoringOccurrenceConflictException;
import com.frauddetection.alert.engineintelligence.EngineIntelligencePendingProjectionProperties;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionService;
import com.frauddetection.alert.engineintelligence.EngineIntelligenceProjectionValidationException;
import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({
        KafkaTopicProperties.class,
        KafkaConsumerProperties.class,
        MlPredictionEvidenceRecoveryProperties.class,
        EngineIntelligenceRecoveryProperties.class,
        EngineIntelligencePendingProjectionProperties.class,
        AssistantProperties.class
})
public class AlertKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertKafkaConfig.class);
    private static final int PLATFORM_TOPIC_PARTITIONS = 3;
    private static final short PLATFORM_TOPIC_REPLICAS = 1;

    @Bean
    public ConsumerFactory<String, TransactionScoredEvent> transactionScoredEventConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());

        return new DefaultKafkaConsumerFactory<>(
                properties,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(new JacksonKafkaDeserializer<>(TransactionScoredEvent.class))
        );
    }

    @Bean
    public ConsumerFactory<String, TransactionScoredEvent> mlPredictionEvidenceConsumerFactory(
            KafkaProperties kafkaProperties
    ) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());

        return new DefaultKafkaConsumerFactory<>(
                properties,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(new JacksonKafkaDeserializer<>(TransactionScoredEvent.class))
        );
    }

    @Bean
    public ProducerFactory<String, TransactionScoredEvent> transactionScoredEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");

        return new DefaultKafkaProducerFactory<>(
                properties,
                new StringSerializer(),
                new JacksonKafkaSerializer<>()
        );
    }

    @Bean
    public KafkaTemplate<String, TransactionScoredEvent> transactionScoredEventKafkaTemplate(
            ProducerFactory<String, TransactionScoredEvent> transactionScoredEventProducerFactory
    ) {
        return new KafkaTemplate<>(transactionScoredEventProducerFactory);
    }

    @Bean
    public ProducerFactory<String, FraudAlertEvent> fraudAlertEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");

        return new DefaultKafkaProducerFactory<>(
                properties,
                new StringSerializer(),
                new JacksonKafkaSerializer<>()
        );
    }

    @Bean
    public KafkaTemplate<String, FraudAlertEvent> fraudAlertEventKafkaTemplate(
            ProducerFactory<String, FraudAlertEvent> fraudAlertEventProducerFactory
    ) {
        return new KafkaTemplate<>(fraudAlertEventProducerFactory);
    }

    @Bean
    public ProducerFactory<String, FraudDecisionEvent> fraudDecisionEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");

        return new DefaultKafkaProducerFactory<>(
                properties,
                new StringSerializer(),
                new JacksonKafkaSerializer<>()
        );
    }

    @Bean
    public KafkaTemplate<String, FraudDecisionEvent> fraudDecisionEventKafkaTemplate(
            ProducerFactory<String, FraudDecisionEvent> fraudDecisionEventProducerFactory
    ) {
        return new KafkaTemplate<>(fraudDecisionEventProducerFactory);
    }

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
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(ProducerFactory<Object, Object> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(kafkaTopicProperties.transactionsDeadLetter(), record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        recoverer.setLogRecoveryRecord(false);
        return recoverer;
    }

    @Bean
    public ConsumerFactory<String, FraudAlertEvent> fraudAlertEvidenceConsumerFactory(
            KafkaProperties kafkaProperties
    ) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        return new DefaultKafkaConsumerFactory<>(
                properties,
                new StringDeserializer(),
                new JacksonKafkaDeserializer<>(FraudAlertEvent.class)
        );
    }

    @Bean
    public DeadLetterPublishingRecoverer mlPredictionEvidenceDeadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            MlPredictionEvidenceRecoveryProperties recoveryProperties
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(
                        evidenceQuarantineRequired(exception)
                                ? recoveryProperties.quarantineTopic()
                                : recoveryProperties.deadLetterTopic(),
                        record.partition()
                )
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        recoverer.setLogRecoveryRecord(false);
        return recoverer;
    }

    @Bean
    public DeadLetterPublishingRecoverer engineIntelligenceDeadLetterPublishingRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            EngineIntelligenceRecoveryProperties recoveryProperties
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(recoveryProperties.deadLetterTopic(), record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        recoverer.setLogRecoveryRecord(false);
        return recoverer;
    }

    @Bean
    public DeadLetterPublishingRecoverer engineIntelligenceRedriveQuarantineRecoverer(
            KafkaOperations<Object, Object> deadLetterKafkaTemplate,
            EngineIntelligenceRecoveryProperties recoveryProperties
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(recoveryProperties.quarantineTopic(), record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        recoverer.setLogRecoveryRecord(false);
        return recoverer;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        long retryAttempts = Math.max((kafkaConsumerProperties.retryAttempts() == null ? 3 : kafkaConsumerProperties.retryAttempts()) - 1L, 0L);
        long retryBackoffMillis = kafkaConsumerProperties.retryBackoffMillis() == null ? 1000L : kafkaConsumerProperties.retryBackoffMillis();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, new FixedBackOff(retryBackoffMillis, retryAttempts));
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MlPredictionEvidencePermanentProcessingException.class,
                ScoringOccurrenceConflictException.class
        );
        errorHandler.setAckAfterHandle(true);
        errorHandler.setRetryListeners((ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) ->
                log.atWarn()
                        .addKeyValue("service", "alert-service")
                        .addKeyValue("topic", record.topic())
                        .addKeyValue("partition", record.partition())
                        .addKeyValue("offset", record.offset())
                        .addKeyValue("deliveryAttempt", deliveryAttempt)
                        .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                        .log("Retrying Kafka record processing before dead-letter handoff."));
        return errorHandler;
    }

    @Bean
    public DefaultErrorHandler mlPredictionEvidenceErrorHandler(
            @Qualifier("mlPredictionEvidenceDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        long retryAttempts = Math.max(
                (kafkaConsumerProperties.retryAttempts() == null ? 3 : kafkaConsumerProperties.retryAttempts()) - 1L,
                0L
        );
        long retryBackoffMillis = kafkaConsumerProperties.retryBackoffMillis() == null
                ? 1000L
                : kafkaConsumerProperties.retryBackoffMillis();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryBackoffMillis, retryAttempts)
        );
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                MlPredictionEvidencePermanentProcessingException.class
        );
        errorHandler.setAckAfterHandle(true);
        errorHandler.setRetryListeners((ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) ->
                log.atWarn()
                        .addKeyValue("service", "alert-service")
                        .addKeyValue("topic", record.topic())
                        .addKeyValue("partition", record.partition())
                        .addKeyValue("offset", record.offset())
                        .addKeyValue("deliveryAttempt", deliveryAttempt)
                        .addKeyValue("exceptionType", exception.getClass().getSimpleName())
                        .log("Retrying ML prediction evidence processing before durable handoff."));
        return errorHandler;
    }

    @Bean
    public DefaultErrorHandler engineIntelligenceErrorHandler(
            @Qualifier("engineIntelligenceDeadLetterPublishingRecoverer")
            DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        long retryAttempts = Math.max(
                (kafkaConsumerProperties.retryAttempts() == null ? 3 : kafkaConsumerProperties.retryAttempts()) - 1L,
                0L
        );
        long retryBackoffMillis = kafkaConsumerProperties.retryBackoffMillis() == null
                ? 1000L
                : kafkaConsumerProperties.retryBackoffMillis();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryBackoffMillis, retryAttempts)
        );
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                EngineIntelligenceProjectionValidationException.class,
                EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException.class
        );
        errorHandler.setAckAfterHandle(true);
        return errorHandler;
    }

    @Bean
    public DefaultErrorHandler engineIntelligenceRedriveErrorHandler(
            @Qualifier("engineIntelligenceRedriveQuarantineRecoverer")
            DeadLetterPublishingRecoverer recoverer,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        long retryAttempts = Math.max(
                (kafkaConsumerProperties.retryAttempts() == null ? 3 : kafkaConsumerProperties.retryAttempts()) - 1L,
                0L
        );
        long retryBackoffMillis = kafkaConsumerProperties.retryBackoffMillis() == null
                ? 1000L
                : kafkaConsumerProperties.retryBackoffMillis();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryBackoffMillis, retryAttempts)
        );
        errorHandler.addNotRetryableExceptions(
                DeserializationException.class,
                EngineIntelligenceRecoveryValidationException.class,
                EngineIntelligenceProjectionValidationException.class,
                EngineIntelligenceProjectionService.SourceOccurrencePayloadConflictException.class
        );
        errorHandler.setAckAfterHandle(true);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> transactionScoredKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory")
            ConsumerFactory<String, TransactionScoredEvent> transactionScoredEventConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionScoredEventConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setConcurrency(kafkaConsumerProperties.concurrency() == null ? 1 : kafkaConsumerProperties.concurrency());
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> mlPredictionEvidenceKafkaListenerContainerFactory(
            @Qualifier("mlPredictionEvidenceConsumerFactory")
            ConsumerFactory<String, TransactionScoredEvent> mlPredictionEvidenceConsumerFactory,
            @Qualifier("mlPredictionEvidenceErrorHandler") DefaultErrorHandler evidenceErrorHandler,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(mlPredictionEvidenceConsumerFactory);
        factory.setCommonErrorHandler(evidenceErrorHandler);
        factory.setConcurrency(kafkaConsumerProperties.concurrency() == null ? 1 : kafkaConsumerProperties.concurrency());
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD
        );
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> engineIntelligenceKafkaListenerContainerFactory(
            @Qualifier("transactionScoredEventConsumerFactory")
            ConsumerFactory<String, TransactionScoredEvent> transactionScoredEventConsumerFactory,
            @Qualifier("engineIntelligenceErrorHandler") DefaultErrorHandler errorHandler,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionScoredEventConsumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(kafkaConsumerProperties.concurrency() == null ? 1 : kafkaConsumerProperties.concurrency());
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD
        );
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> engineIntelligenceRedriveKafkaListenerContainerFactory(
            @Qualifier("mlPredictionEvidenceConsumerFactory")
            ConsumerFactory<String, TransactionScoredEvent> consumerFactory,
            @Qualifier("engineIntelligenceRedriveErrorHandler") DefaultErrorHandler errorHandler,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(kafkaConsumerProperties.concurrency() == null ? 1 : kafkaConsumerProperties.concurrency());
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD
        );
        return factory;
    }

    @Bean
    public KafkaAdmin.NewTopics alertTopics(
            KafkaTopicProperties kafkaTopicProperties,
            MlPredictionEvidenceRecoveryProperties recoveryProperties,
            EngineIntelligenceRecoveryProperties engineIntelligenceRecoveryProperties
    ) {
        return new KafkaAdmin.NewTopics(
                new NewTopic(kafkaTopicProperties.transactionScored(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.fraudAlerts(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.fraudDecisions(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.transactionsDeadLetter(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(recoveryProperties.deadLetterTopic(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(recoveryProperties.redriveTopic(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(recoveryProperties.quarantineTopic(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(
                        engineIntelligenceRecoveryProperties.deadLetterTopic(),
                        PLATFORM_TOPIC_PARTITIONS,
                        PLATFORM_TOPIC_REPLICAS
                ),
                new NewTopic(
                        engineIntelligenceRecoveryProperties.redriveTopic(),
                        PLATFORM_TOPIC_PARTITIONS,
                        PLATFORM_TOPIC_REPLICAS
                ),
                new NewTopic(
                        engineIntelligenceRecoveryProperties.quarantineTopic(),
                        PLATFORM_TOPIC_PARTITIONS,
                        PLATFORM_TOPIC_REPLICAS
                )
        );
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
