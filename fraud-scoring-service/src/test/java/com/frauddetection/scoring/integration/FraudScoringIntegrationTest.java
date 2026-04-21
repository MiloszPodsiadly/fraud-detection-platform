package com.frauddetection.scoring.integration;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class FraudScoringIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, TransactionEnrichedEvent> enrichedEventKafkaTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("app.kafka.topics.transaction-enriched", () -> "transactions.enriched.scoring.test");
        registry.add("app.kafka.topics.transaction-scored", () -> "transactions.scored.test");
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.scoring.test");
    }

    @Test
    void shouldConsumeEnrichedEventAndPublishScoredEvent() {
        TransactionEnrichedEvent enrichedEvent = TransactionFixtures.enrichedTransaction().build();
        enrichedEventKafkaTemplate.send("transactions.enriched.scoring.test", enrichedEvent.transactionId(), enrichedEvent);
        enrichedEventKafkaTemplate.flush();

        ConsumerRecord<String, TransactionScoredEvent> record = pollSingleScoredRecord();

        assertThat(record.value().transactionId()).isEqualTo(enrichedEvent.transactionId());
        assertThat(record.value().correlationId()).isEqualTo(enrichedEvent.correlationId());
        assertThat(record.value().fraudScore()).isGreaterThan(0.0d);
        assertThat(record.value().riskLevel()).isNotNull();
        assertThat(record.value().featureSnapshot()).isEqualTo(enrichedEvent.featureSnapshot());
    }

    private ConsumerRecord<String, TransactionScoredEvent> pollSingleScoredRecord() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "fraud-scoring-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                JsonDeserializer.TRUSTED_PACKAGES, "com.frauddetection.common.events",
                JsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionScoredEvent.class.getName()
        );

        try (KafkaConsumer<String, TransactionScoredEvent> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("transactions.scored.test"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, TransactionScoredEvent> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }

        throw new AssertionError("No scored event was published within the timeout.");
    }
}
