package com.frauddetection.alert.messaging;

import com.frauddetection.alert.outbox.FraudAlertOutboxRecord;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class TransactionScoredRedriveKafkaMongoIntegrationTest extends AbstractIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String SOURCE_TOPIC = "transactions.scored.redrive-it." + SUFFIX;
    private static final String REDRIVE_TOPIC = "transactions.redrive.it." + SUFFIX;
    private static final String QUARANTINE_TOPIC = "transactions.quarantine.it." + SUFFIX;
    private static final String SOURCE_GROUP = "alert-source-it-" + SUFFIX;
    private static final String REDRIVE_GROUP = "alert-redrive-it-" + SUFFIX;

    @Autowired
    private KafkaTemplate<String, TransactionScoredEvent> kafkaTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.kafka.consumer.group-id", () -> SOURCE_GROUP);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.mongodb.uri", () -> FraudPlatformContainers.mongodb()
                .getReplicaSetUrl("redrive_it_" + SUFFIX.substring(0, 16)));
        registry.add("app.kafka.topics.transaction-scored", () -> SOURCE_TOPIC);
        registry.add("app.kafka.topics.fraud-alerts", () -> "fraud.alerts.redrive-it." + SUFFIX);
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.redrive-it." + SUFFIX);
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dlt.redrive-it." + SUFFIX);
        registry.add("app.kafka.transaction-scored-recovery.redrive-topic", () -> REDRIVE_TOPIC);
        registry.add("app.kafka.transaction-scored-recovery.quarantine-topic", () -> QUARANTINE_TOPIC);
        registry.add("app.kafka.transaction-scored-recovery.redrive-group-id", () -> REDRIVE_GROUP);
        registry.add("app.kafka.transaction-scored-recovery.source-consumer-group", () -> SOURCE_GROUP);
        registry.add("app.kafka.transaction-scored-recovery.redrive-enabled", () -> true);
    }

    @Test
    void replayUsesAuthoritativeMongoPathAndInvalidProvenanceIsDurablyQuarantined() throws Exception {
        String transactionId = "transaction-redrive-" + SUFFIX;
        TransactionScoredEvent event = TransactionFixtures.scoredTransaction()
                .withTransactionId(transactionId)
                .withFeatureSnapshot(rapidTransferSnapshot(transactionId))
                .build();

        try (KafkaConsumer<String, byte[]> quarantineConsumer = quarantineConsumer()) {
            awaitRedriveListenerReady();
            quarantineConsumer.subscribe(List.of(QUARANTINE_TOPIC));
            awaitConsumerAssignment(quarantineConsumer);

            kafkaTemplate.send(validRedriveRecord(event)).get(10, TimeUnit.SECONDS);
            kafkaTemplate.send(validRedriveRecord(event)).get(10, TimeUnit.SECONDS);
            kafkaTemplate.flush();

            awaitCondition(
                    () -> countByTransactionId(ScoredTransactionDocument.class, transactionId) == 1L,
                    "one scored transaction",
                    event
            );
            awaitCondition(
                    () -> countByTransactionId(AlertDocument.class, transactionId) == 1L,
                    "one alert",
                    event
            );
            awaitCondition(
                    () -> countByTransactionId(FraudAlertOutboxRecord.class, transactionId) == 1L,
                    "one fraud alert outbox record",
                    event
            );
            awaitCondition(
                    () -> countFraudCasesByCustomerId(event.customerId()) == 1L,
                    "one eligible fraud case",
                    event
            );

            assertThat(countByTransactionId(ScoredTransactionDocument.class, transactionId)).isOne();
            assertThat(countByTransactionId(AlertDocument.class, transactionId)).isOne();
            assertThat(countByTransactionId(FraudAlertOutboxRecord.class, transactionId)).isOne();
            assertThat(countFraudCasesByCustomerId(event.customerId())).isOne();

            ProducerRecord<String, TransactionScoredEvent> invalid = new ProducerRecord<>(
                    REDRIVE_TOPIC,
                    null,
                    "invalid-" + transactionId,
                    TransactionFixtures.scoredTransaction().withTransactionId("invalid-" + transactionId).build(),
                    new RecordHeaders()
            );
            addStringHeader(invalid, KafkaHeaders.DLT_ORIGINAL_TOPIC, SOURCE_TOPIC);
            addStringHeader(invalid, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, SOURCE_GROUP);
            addIntHeader(invalid, KafkaHeaders.DLT_ORIGINAL_PARTITION, 1);
            kafkaTemplate.send(invalid).get(10, TimeUnit.SECONDS);
            kafkaTemplate.flush();

            ConsumerRecord<String, byte[]> quarantined = pollForRecord(quarantineConsumer);
            assertThat(quarantined).isNotNull();
            assertThat(quarantined.topic()).isEqualTo(QUARANTINE_TOPIC);
            assertThat(quarantined.value()).isNotEmpty();
            assertThat(headerText(quarantined, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(REDRIVE_TOPIC);
            assertThat(quarantined.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
            assertThat(quarantined.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
        }
    }

    private ProducerRecord<String, TransactionScoredEvent> validRedriveRecord(TransactionScoredEvent event) {
        ProducerRecord<String, TransactionScoredEvent> record = new ProducerRecord<>(
                REDRIVE_TOPIC,
                null,
                event.transactionId(),
                event,
                new RecordHeaders()
        );
        addStringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC, SOURCE_TOPIC);
        addStringHeader(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, SOURCE_GROUP);
        addIntHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION, 1);
        record.headers().add(
                KafkaHeaders.DLT_ORIGINAL_OFFSET,
                ByteBuffer.allocate(Long.BYTES).putLong(42L).array()
        );
        return record;
    }

    private void addStringHeader(ProducerRecord<?, ?> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private void addIntHeader(ProducerRecord<?, ?> record, String name, int value) {
        record.headers().add(name, ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private KafkaConsumer<String, byte[]> quarantineConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "quarantine-observer-" + SUFFIX,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"
        ), new StringDeserializer(), new ByteArrayDeserializer());
    }

    private Map<String, Object> rapidTransferSnapshot(String transactionId) {
        return Map.of(
                FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS,
                List.of("prior-" + transactionId, transactionId),
                FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2,
                FraudFeatureContract.RECENT_TRANSACTION_COUNT_WINDOW, "PT1M",
                FraudFeatureContract.RECENT_AMOUNT_SUM_PLN, new BigDecimal("20000.00"),
                FraudFeatureContract.RECENT_AMOUNT_SUM_WINDOW, "PT1M",
                FraudFeatureContract.CURRENT_TRANSACTION_AMOUNT_PLN, new BigDecimal("10000.00")
        );
    }

    private void awaitRedriveListenerReady() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer("transactionScoredRedriveListener");
        assertThat(container).as("redrive listener container").isNotNull();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (container.isRunning() && !container.getAssignedPartitions().isEmpty()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        throw new AssertionError("Redrive listener was not ready: running=" + container.isRunning()
                + ", assignedPartitions=" + container.getAssignedPartitions());
    }

    private void awaitConsumerAssignment(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(100));
            if (!consumer.assignment().isEmpty()) {
                return;
            }
        }
        throw new AssertionError("Quarantine observer was not assigned: topic=" + QUARANTINE_TOPIC
                + ", assignment=" + consumer.assignment());
    }

    private ConsumerRecord<String, byte[]> pollForRecord(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        return null;
    }

    private String headerText(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private long countByTransactionId(Class<?> documentType, String transactionId) {
        return mongoTemplate.count(
                Query.query(Criteria.where("transactionId").is(transactionId)),
                documentType
        );
    }

    private long countFraudCasesByCustomerId(String customerId) {
        return mongoTemplate.count(
                Query.query(Criteria.where("customerId").is(customerId)),
                FraudCaseDocument.class
        );
    }

    private void awaitCondition(
            BooleanSupplier condition,
            String expectedState,
            TransactionScoredEvent event
    ) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        MessageListenerContainer container = listenerRegistry.getListenerContainer("transactionScoredRedriveListener");
        throw new AssertionError("Timed out waiting for " + expectedState
                + ": topic=" + REDRIVE_TOPIC
                + ", group=" + REDRIVE_GROUP
                + ", listenerRunning=" + (container != null && container.isRunning())
                + ", assignedPartitions=" + (container == null ? List.of() : container.getAssignedPartitions())
                + ", scoredTransactions=" + countByTransactionId(ScoredTransactionDocument.class, event.transactionId())
                + ", alerts=" + countByTransactionId(AlertDocument.class, event.transactionId())
                + ", outboxRecords=" + countByTransactionId(FraudAlertOutboxRecord.class, event.transactionId())
                + ", fraudCases=" + countFraudCasesByCustomerId(event.customerId()));
    }
}
