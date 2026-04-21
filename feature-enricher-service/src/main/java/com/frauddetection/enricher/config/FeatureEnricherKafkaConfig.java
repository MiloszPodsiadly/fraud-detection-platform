package com.frauddetection.enricher.config;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({
        KafkaTopicProperties.class,
        KafkaConsumerProperties.class,
        FeatureStoreProperties.class
})
public class FeatureEnricherKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(FeatureEnricherKafkaConfig.class);
    private static final String TRUSTED_EVENT_PACKAGES = "com.frauddetection.common.events.contract,com.frauddetection.common.events.model,com.frauddetection.common.events.enums";
    private static final int PLATFORM_TOPIC_PARTITIONS = 3;
    private static final short PLATFORM_TOPIC_REPLICAS = 1;

    @Bean
    public ConsumerFactory<String, TransactionRawEvent> transactionRawEventConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionRawEvent.class.getName());
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, TRUSTED_EVENT_PACKAGES);
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    public ProducerFactory<String, TransactionRawEvent> transactionRawEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        properties.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, TransactionRawEvent> transactionRawEventKafkaTemplate(
            ProducerFactory<String, TransactionRawEvent> transactionRawEventProducerFactory
    ) {
        return new KafkaTemplate<>(transactionRawEventProducerFactory);
    }

    @Bean
    public ProducerFactory<String, TransactionEnrichedEvent> transactionEnrichedEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        properties.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    public KafkaTemplate<String, TransactionEnrichedEvent> transactionEnrichedEventKafkaTemplate(
            ProducerFactory<String, TransactionEnrichedEvent> transactionEnrichedEventProducerFactory
    ) {
        return new KafkaTemplate<>(transactionEnrichedEventProducerFactory);
    }

    @Bean
    public ProducerFactory<Object, Object> deadLetterProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        properties.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(properties);
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
                        .addKeyValue("service", "feature-enricher-service")
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
    public ConcurrentKafkaListenerContainerFactory<String, TransactionRawEvent> transactionRawKafkaListenerContainerFactory(
            ConsumerFactory<String, TransactionRawEvent> transactionRawEventConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler,
            KafkaConsumerProperties kafkaConsumerProperties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionRawEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionRawEventConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setConcurrency(kafkaConsumerProperties.concurrency() == null ? 1 : kafkaConsumerProperties.concurrency());
        return factory;
    }

    @Bean
    public KafkaAdmin.NewTopics featureEnricherTopics(KafkaTopicProperties kafkaTopicProperties) {
        return new KafkaAdmin.NewTopics(
                new NewTopic(kafkaTopicProperties.transactionRaw(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.transactionEnriched(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS),
                new NewTopic(kafkaTopicProperties.transactionsDeadLetter(), PLATFORM_TOPIC_PARTITIONS, PLATFORM_TOPIC_REPLICAS)
        );
    }
}
