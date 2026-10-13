package com.frauddetection.alert.config;

import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class TransactionScoredKafkaConfiguration {

    @Bean
    public ConsumerFactory<String, TransactionScoredEvent> transactionScoredEventConsumerFactory(
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
    public ProducerFactory<String, TransactionScoredEvent> transactionScoredEventProducerFactory(
            KafkaProperties kafkaProperties
    ) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new JacksonKafkaSerializer<>());
    }

    @Bean
    public KafkaTemplate<String, TransactionScoredEvent> transactionScoredEventKafkaTemplate(
            ProducerFactory<String, TransactionScoredEvent> transactionScoredEventProducerFactory
    ) {
        return new KafkaTemplate<>(transactionScoredEventProducerFactory);
    }
}
