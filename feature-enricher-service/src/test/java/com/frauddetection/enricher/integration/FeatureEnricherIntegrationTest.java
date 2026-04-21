package com.frauddetection.enricher.integration;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
class FeatureEnricherIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private KafkaTemplate<String, TransactionRawEvent> rawEventKafkaTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.data.redis.host", () -> FraudPlatformContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> FraudPlatformContainers.redis().getMappedPort(6379));
        registry.add("app.kafka.topics.transaction-raw", () -> "transactions.raw.test");
        registry.add("app.kafka.topics.transaction-enriched", () -> "transactions.enriched.test");
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.test");
    }

    @AfterEach
    void clearRedis() {
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void shouldConsumeRawEventEnrichItAndPersistFeatureStateInRedis() {
        TransactionRawEvent rawEvent = TransactionFixtures.rawTransaction().build();
        rawEventKafkaTemplate.send("transactions.raw.test", rawEvent.transactionId(), rawEvent);
        rawEventKafkaTemplate.flush();

        ConsumerRecord<String, TransactionEnrichedEvent> record = pollSingleEnrichedRecord();

        assertThat(record.value().transactionId()).isEqualTo(rawEvent.transactionId());
        assertThat(record.value().correlationId()).isEqualTo(rawEvent.correlationId());
        assertThat(record.value().recentTransactionCount()).isEqualTo(1);
        assertThat(record.value().merchantFrequency7d()).isEqualTo(1);
        assertThat(record.value().featureSnapshot()).containsEntry("currency", rawEvent.transactionAmount().currency());

        String deviceKey = "feature:customer:" + rawEvent.customerId() + ":devices";
        assertThat(stringRedisTemplate.opsForSet().isMember(deviceKey, rawEvent.deviceInfo().deviceId())).isTrue();
    }

    private ConsumerRecord<String, TransactionEnrichedEvent> pollSingleEnrichedRecord() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "feature-enricher-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                JsonDeserializer.TRUSTED_PACKAGES, "com.frauddetection.common.events",
                JsonDeserializer.USE_TYPE_INFO_HEADERS, false,
                JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionEnrichedEvent.class.getName()
        );

        try (KafkaConsumer<String, TransactionEnrichedEvent> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("transactions.enriched.test"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, TransactionEnrichedEvent> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }

        throw new AssertionError("No enriched event was published within the timeout.");
    }
}
