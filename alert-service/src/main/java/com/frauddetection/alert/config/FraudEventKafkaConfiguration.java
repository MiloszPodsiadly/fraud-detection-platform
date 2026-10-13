package com.frauddetection.alert.config;

import com.frauddetection.common.events.contract.FraudAlertEvent;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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

import java.util.HashMap;
import java.util.Map;

@Configuration
public class FraudEventKafkaConfiguration {

    @Bean
    public ProducerFactory<String, FraudAlertEvent> fraudAlertEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = producerProperties(kafkaProperties);
        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new JacksonKafkaSerializer<>());
    }

    @Bean
    public KafkaTemplate<String, FraudAlertEvent> fraudAlertEventKafkaTemplate(
            ProducerFactory<String, FraudAlertEvent> fraudAlertEventProducerFactory
    ) {
        return new KafkaTemplate<>(fraudAlertEventProducerFactory);
    }

    @Bean
    public ProducerFactory<String, FraudDecisionEvent> fraudDecisionEventProducerFactory(
            KafkaProperties kafkaProperties
    ) {
        Map<String, Object> properties = producerProperties(kafkaProperties);
        return new DefaultKafkaProducerFactory<>(properties, new StringSerializer(), new JacksonKafkaSerializer<>());
    }

    @Bean
    public KafkaTemplate<String, FraudDecisionEvent> fraudDecisionEventKafkaTemplate(
            ProducerFactory<String, FraudDecisionEvent> fraudDecisionEventProducerFactory
    ) {
        return new KafkaTemplate<>(fraudDecisionEventProducerFactory);
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

    private Map<String, Object> producerProperties(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        return properties;
    }
}
