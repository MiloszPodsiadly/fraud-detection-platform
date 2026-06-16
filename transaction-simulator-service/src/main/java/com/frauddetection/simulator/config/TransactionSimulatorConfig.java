package com.frauddetection.simulator.config;

import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({
        KafkaTopicProperties.class,
        ReplayProperties.class,
        AutoReplayProperties.class,
        JsonlReplayProperties.class,
        SyntheticReplayProperties.class
})
public class TransactionSimulatorConfig {

    @Bean
    public ProducerFactory<String, TransactionRawEvent> transactionRawEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");

        return new DefaultKafkaProducerFactory<>(
                properties,
                new StringSerializer(),
                new JacksonKafkaSerializer<>()
        );
    }

    @Bean
    public KafkaTemplate<String, TransactionRawEvent> transactionRawEventKafkaTemplate(
            ProducerFactory<String, TransactionRawEvent> transactionRawEventProducerFactory
    ) {
        return new KafkaTemplate<>(transactionRawEventProducerFactory);
    }

    @Bean
    public TaskExecutor replayTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("replay-executor-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }
}