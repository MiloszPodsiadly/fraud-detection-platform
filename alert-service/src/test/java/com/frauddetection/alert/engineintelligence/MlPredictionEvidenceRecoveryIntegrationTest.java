package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.config.AlertKafkaConfig;
import com.frauddetection.alert.config.KafkaConsumerProperties;
import com.frauddetection.alert.config.MlPredictionEvidenceRecoveryProperties;
import com.frauddetection.alert.messaging.MlPredictionEvidenceTransientProcessingException;
import com.frauddetection.alert.outbox.FraudAlertOutboxRecord;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class MlPredictionEvidenceRecoveryIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String SOURCE_TOPIC = "transactions.scored.evidence-recovery." + SUFFIX;
    private static final String EVIDENCE_DLT = "ml.prediction-evidence.dead-letter." + SUFFIX;
    private static final String REDRIVE_TOPIC = "ml.prediction-evidence.redrive." + SUFFIX;
    private static final String QUARANTINE_TOPIC = "ml.prediction-evidence.quarantine." + SUFFIX;
    private static final String SOURCE_GROUP = "evidence-source." + SUFFIX;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private AlertKafkaConfig kafkaConfig;

    @MockitoBean
    private MlPredictionEvidenceProjectionRepository evidenceRepository;

    private final AtomicBoolean evidenceStoreUnavailable = new AtomicBoolean(true);
    private final AtomicInteger insertAttempts = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.mongodb.uri", () -> FraudPlatformContainers.mongodb().getReplicaSetUrl("evidence_recovery_test"));
        registry.add("spring.data.redis.host", () -> FraudPlatformContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> FraudPlatformContainers.redis().getMappedPort(6379));
        registry.add("app.kafka.topics.transaction-scored", () -> SOURCE_TOPIC);
        registry.add("app.kafka.topics.fraud-alerts", () -> "fraud.alerts.evidence-recovery." + SUFFIX);
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.evidence-recovery." + SUFFIX);
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.evidence-recovery." + SUFFIX);
        registry.add("app.kafka.consumer.ml-prediction-evidence-group-id", () -> SOURCE_GROUP);
        registry.add("app.kafka.consumer.retry-attempts", () -> 2);
        registry.add("app.kafka.consumer.retry-backoff-millis", () -> 25);
        registry.add("app.kafka.evidence-recovery.dead-letter-topic", () -> EVIDENCE_DLT);
        registry.add("app.kafka.evidence-recovery.redrive-topic", () -> REDRIVE_TOPIC);
        registry.add("app.kafka.evidence-recovery.quarantine-topic", () -> QUARANTINE_TOPIC);
        registry.add("app.kafka.evidence-recovery.redrive-group-id", () -> "evidence-redrive." + SUFFIX);
        registry.add("app.kafka.evidence-recovery.redrive-enabled", () -> true);
    }

    @BeforeEach
    void configureFaultInjectedEvidenceStore() {
        mongoTemplate.dropCollection(MlPredictionEvidenceProjection.class);
        evidenceStoreUnavailable.set(true);
        insertAttempts.set(0);
        when(evidenceRepository.insert(any(MlPredictionEvidenceProjection.class))).thenAnswer(invocation -> {
            insertAttempts.incrementAndGet();
            if (evidenceStoreUnavailable.get()) {
                throw new DataAccessResourceFailureException("simulated private evidence store outage");
            }
            return mongoTemplate.insert(invocation.getArgument(0, MlPredictionEvidenceProjection.class));
        });
        when(evidenceRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(
                mongoTemplate.findById(invocation.getArgument(0, String.class), MlPredictionEvidenceProjection.class)
        ));
    }

    @Test
    void durableEvidenceOnlyRedriveSurvivesRestartAndDoesNotRepeatBaselineEffects() throws Exception {
        TransactionScoredEvent event = MlPredictionEvidenceProjectionTestSupport.event(
                "evidence-recovery-event-1",
                "evidence-recovery-transaction-1",
                "evidence-recovery-correlation-1",
                0.8123d,
                "model-v1",
                MlPredictionEvidenceProjectionTestSupport.EVENT_CREATED_AT
        );
        sendBytes(SOURCE_TOPIC, event.transactionId(), serialize(SOURCE_TOPIC, event), new RecordHeaders());

        ConsumerRecord<String, byte[]> deadLetter = awaitRecord(EVIDENCE_DLT, event.transactionId());
        assertOriginalCoordinates(deadLetter, SOURCE_TOPIC);
        awaitCommittedSourceOffset(deadLetter);
        assertThat(mongoTemplate.count(new Query(), MlPredictionEvidenceProjection.class)).isZero();

        MessageListenerContainer sourceListener = listenerRegistry.getListenerContainer(
                "mlPredictionEvidenceSourceListener"
        );
        assertThat(sourceListener).isNotNull();
        sourceListener.stop();
        await(() -> !sourceListener.isRunning(), "evidence source listener did not stop");
        int attemptsAfterHandoff = insertAttempts.get();
        sourceListener.start();
        await(sourceListener::isRunning, "evidence source listener did not restart");
        awaitStableAttempts(attemptsAfterHandoff);

        awaitBaselineEffects(event.transactionId());
        EffectCounts beforeRedrive = effectCounts(event.transactionId(), event.correlationId());

        evidenceStoreUnavailable.set(false);
        copyToRedrive(deadLetter);
        await(() -> mongoTemplate.exists(
                Query.query(Criteria.where("_id").is(event.eventId())),
                MlPredictionEvidenceProjection.class
        ), "evidence projection was not restored");
        copyToRedrive(deadLetter);
        awaitStableEvidenceCount(1L);

        MlPredictionEvidenceProjection stored = mongoTemplate.findById(
                event.eventId(),
                MlPredictionEvidenceProjection.class
        );
        assertThat(stored).isNotNull();
        assertThat(stored.getMlScore()).isEqualTo(event.mlPredictionEvidence().mlScore());
        assertThat(stored.getModelName()).isEqualTo(event.mlPredictionEvidence().modelName());
        assertThat(stored.getModelVersion()).isEqualTo(event.mlPredictionEvidence().modelVersion());
        assertThat(stored.getSourceEventCreatedAt()).isEqualTo(event.createdAt());
        assertThat(stored.getSourceExecutionTimestamp())
                .isEqualTo(event.mlPredictionEvidence().sourceExecutionTimestamp());
        assertThat(effectCounts(event.transactionId(), event.correlationId())).isEqualTo(beforeRedrive);

        byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8);
        sendBytes(SOURCE_TOPIC, "invalid-evidence", malformed, new RecordHeaders());
        ConsumerRecord<String, byte[]> quarantined = awaitRecord(QUARANTINE_TOPIC, "invalid-evidence");
        assertThat(quarantined.value()).containsExactly(malformed);
        assertOriginalCoordinates(quarantined, SOURCE_TOPIC);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedDeadLetterHandoffLeavesRealKafkaSourceOffsetUncommitted() throws Exception {
        String sourceTopic = "ml.prediction-evidence.handoff-failure." + UUID.randomUUID();
        String groupId = "evidence-handoff-failure." + UUID.randomUUID();
        TransactionScoredEvent event = MlPredictionEvidenceProjectionTestSupport.event(
                "evidence-handoff-failure-event",
                "evidence-handoff-failure-transaction",
                "evidence-handoff-failure-correlation",
                0.8123d,
                "model-v1",
                MlPredictionEvidenceProjectionTestSupport.EVENT_CREATED_AT
        );
        sendBytes(sourceTopic, event.transactionId(), serialize(sourceTopic, event), new RecordHeaders());

        KafkaOperations<Object, Object> failingKafkaOperations = mock(KafkaOperations.class);
        when(failingKafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("simulated evidence DLT outage")
                ));
        DeadLetterPublishingRecoverer recoverer = kafkaConfig.mlPredictionEvidenceDeadLetterPublishingRecoverer(
                failingKafkaOperations,
                recoveryProperties()
        );
        DefaultErrorHandler errorHandler = kafkaConfig.mlPredictionEvidenceErrorHandler(
                recoverer,
                new KafkaConsumerProperties(1, 1, 0L)
        );
        ConcurrentKafkaListenerContainerFactory<String, TransactionScoredEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(failureProofConsumerFactory(groupId));
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var container = factory.createContainer(sourceTopic);
        AtomicInteger deliveries = new AtomicInteger();
        AtomicReference<TopicPartition> deliveredPartition = new AtomicReference<>();
        container.getContainerProperties().setMessageListener(
                (MessageListener<String, TransactionScoredEvent>) record -> {
                    deliveries.incrementAndGet();
                    deliveredPartition.set(new TopicPartition(record.topic(), record.partition()));
                    throw new MlPredictionEvidenceTransientProcessingException(
                            MlPredictionEvidenceProjectionReason.STORE_UNAVAILABLE
                    );
                }
        );

        try {
            container.start();
            await(() -> deliveries.get() >= 2, "failed handoff record was not retained for retry");
            TopicPartition partition = deliveredPartition.get();
            assertThat(partition).isNotNull();
            assertThat(committedOffset(groupId, partition)).isLessThanOrEqualTo(0L);
        } finally {
            container.stop();
        }
    }

    private void awaitBaselineEffects(String transactionId) {
        await(() -> mongoTemplate.count(
                Query.query(Criteria.where("transactionId").is(transactionId)),
                AlertDocument.class
        ) == 1L, "baseline alert was not created");
    }

    private EffectCounts effectCounts(String transactionId, String correlationId) {
        return new EffectCounts(
                mongoTemplate.count(Query.query(Criteria.where("transactionId").is(transactionId)), AlertDocument.class),
                mongoTemplate.count(Query.query(Criteria.where("transactionIds").is(transactionId)), FraudCaseDocument.class),
                mongoTemplate.count(Query.query(Criteria.where("correlation_id").is(correlationId)), AuditEventDocument.class),
                mongoTemplate.count(Query.query(Criteria.where("transactionId").is(transactionId)), FraudAlertOutboxRecord.class)
        );
    }

    private void awaitCommittedSourceOffset(ConsumerRecord<String, byte[]> deadLetter) {
        int sourcePartition = intHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        long sourceOffset = longHeader(deadLetter, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        TopicPartition source = new TopicPartition(SOURCE_TOPIC, sourcePartition);
        await(() -> {
            try (AdminClient admin = AdminClient.create(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    FraudPlatformContainers.kafka().getBootstrapServers()
            ))) {
                var committed = admin.listConsumerGroupOffsets(SOURCE_GROUP)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS)
                        .get(source);
                return committed != null && committed.offset() >= sourceOffset + 1L;
            } catch (Exception exception) {
                return false;
            }
        }, "evidence source offset was not committed after DLT handoff");
    }

    private DefaultKafkaConsumerFactory<String, TransactionScoredEvent> failureProofConsumerFactory(String groupId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(
                properties,
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(
                        new com.frauddetection.common.events.kafka.JacksonKafkaDeserializer<>(
                                TransactionScoredEvent.class
                        )
                )
        );
    }

    private long committedOffset(String groupId, TopicPartition partition) {
        try (AdminClient admin = AdminClient.create(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                FraudPlatformContainers.kafka().getBootstrapServers()
        ))) {
            var committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS)
                    .get(partition);
            return committed == null ? 0L : committed.offset();
        } catch (Exception exception) {
            throw new AssertionError("Kafka consumer offset could not be inspected", exception);
        }
    }

    private MlPredictionEvidenceRecoveryProperties recoveryProperties() {
        return new MlPredictionEvidenceRecoveryProperties(
                EVIDENCE_DLT,
                REDRIVE_TOPIC,
                QUARANTINE_TOPIC,
                "evidence-redrive." + SUFFIX,
                true
        );
    }

    private void assertOriginalCoordinates(ConsumerRecord<String, byte[]> record, String expectedTopic) {
        assertThat(stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(expectedTopic);
        assertThat(intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION)).isGreaterThanOrEqualTo(0);
        assertThat(longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET)).isGreaterThanOrEqualTo(0L);
    }

    private void copyToRedrive(ConsumerRecord<String, byte[]> deadLetter) throws Exception {
        sendBytes(
                REDRIVE_TOPIC,
                deadLetter.key(),
                deadLetter.value(),
                new RecordHeaders(deadLetter.headers())
        );
    }

    private byte[] serialize(String topic, TransactionScoredEvent event) {
        return new JacksonKafkaSerializer<TransactionScoredEvent>().serialize(topic, event);
    }

    private void sendBytes(String topic, String key, byte[] value, RecordHeaders headers) throws Exception {
        Map<String, Object> properties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"
        );
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
                properties,
                new StringSerializer(),
                new ByteArraySerializer()
        )) {
            producer.send(new ProducerRecord<>(topic, null, key, value, headers)).get(10, TimeUnit.SECONDS);
        }
    }

    private ConsumerRecord<String, byte[]> awaitRecord(String topic, String key) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "evidence-inspection-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        );
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new ByteArrayDeserializer()
        )) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Kafka record was not available on " + topic);
    }

    private void awaitStableAttempts(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(insertAttempts.get()).isEqualTo(expected);
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
    }

    private void awaitStableEvidenceCount(long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(mongoTemplate.count(new Query(), MlPredictionEvidenceProjection.class)).isEqualTo(expected);
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
    }

    private void await(BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        throw new AssertionError(failureMessage);
    }

    private String stringHeader(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private int intHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getInt();
    }

    private long longHeader(ConsumerRecord<?, ?> record, String name) {
        return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getLong();
    }

    private record EffectCounts(long alerts, long fraudCases, long auditEvents, long alertOutboxRecords) {
    }
}
