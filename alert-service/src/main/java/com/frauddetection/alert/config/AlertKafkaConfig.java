package com.frauddetection.alert.config;

import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({
        KafkaTopicProperties.class,
        KafkaConsumerProperties.class,
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
                new JacksonKafkaDeserializer<>(TransactionScoredEvent.class)
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

        return new DefaultKafkaProducerFactory<>(
                properties,
                keySerializer,
                new JacksonKafkaSerializer<>()
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
        return new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(kafkaTopicProperties.transactionsDeadLetter(), record.partition())
        );
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        long retryAttempts = Math.max((kafkaConsumerProperties.retryAttempts() == null ? 3 : kafkaConsumerProperties.retryAttempts()) - 1L, 0L);
        long retryBackoffMillis = kafkaConsumerProperties.retryBackoffMillis() == null ? 1000L : kafkaConsumerProperties.retryBackoffMillis();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, new FixedBackOff(retryBackoffMillis, retryAttempts));
        errorHandler.setRetryListeners((ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) ->
                log.atWarn()
                        .addKeyValue("service", "alert-service")
                        .addKeyValue("topic", record.topic())
                        .addKeyValue("partition", record.partition())
                        .addKeyValue("offset", record.offset())
                        .addKeyValue("key", record.key())
                        .addKeyValue("deliveryAttempt", deliveryAttempt)
                        .setCause(exception)
                        .log("Retrying Kafka record processing before dead-letter handoff."));
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> transactionScoredKafkaListenerContainerFactory(
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
    public KafkaAdmin.NewTopics alertTopics(KafkaTopicProperties kafkaTopicProperties) {
        return new KafkaAdmin.NewTopics(
                new NewTopic(kafkaTopicProperties.transactionScored(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.fraudAlerts(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.fraudDecisions(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.transactionsDeadLetter(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS)
        );
    }
}