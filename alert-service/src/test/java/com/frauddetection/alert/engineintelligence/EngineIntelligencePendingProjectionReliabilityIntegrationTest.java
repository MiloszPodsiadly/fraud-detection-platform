package com.frauddetection.alert.engineintelligence;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.FraudCaseDocument;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
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
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class EngineIntelligencePendingProjectionReliabilityIntegrationTest {

    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String SOURCE_TOPIC = "transactions.scored.ei-pending." + SUFFIX;
    private static final String EI_DLT = "engine-intelligence.dead-letter.ei-pending." + SUFFIX;
    private static final String EI_REDRIVE = "engine-intelligence.redrive.ei-pending." + SUFFIX;
    private static final String EI_QUARANTINE = "engine-intelligence.quarantine.ei-pending." + SUFFIX;
    private static final String EI_SOURCE_GROUP = "engine-intelligence.ei-pending." + SUFFIX;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired private EngineIntelligenceProjectionService projectionService;
    @Autowired private EngineIntelligencePendingProjectionProperties pendingProperties;
    @Autowired private AlertServiceMetrics metrics;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FraudPlatformContainers.startAll();
        registry.add("spring.kafka.bootstrap-servers", () -> FraudPlatformContainers.kafka().getBootstrapServers());
        registry.add("spring.kafka.consumer.group-id", () -> "authoritative-scoring.ei-pending." + SUFFIX);
        registry.add("spring.mongodb.uri", () -> FraudPlatformContainers.mongodb().getReplicaSetUrl("ei_pending_test"));
        registry.add("spring.data.redis.host", () -> FraudPlatformContainers.redis().getHost());
        registry.add("spring.data.redis.port", () -> FraudPlatformContainers.redis().getMappedPort(6379));
        registry.add("app.kafka.topics.transaction-scored", () -> SOURCE_TOPIC);
        registry.add("app.kafka.topics.fraud-alerts", () -> "fraud.alerts.ei-pending." + SUFFIX);
        registry.add("app.kafka.topics.fraud-decisions", () -> "fraud.decisions.ei-pending." + SUFFIX);
        registry.add("app.kafka.topics.transactions-dead-letter", () -> "transactions.dead-letter.ei-pending." + SUFFIX);
        registry.add("app.kafka.consumer.engine-intelligence-group-id", () -> EI_SOURCE_GROUP);
        registry.add("app.kafka.consumer.ml-prediction-evidence-group-id", () -> "ml-evidence.ei-pending." + SUFFIX);
        registry.add("app.kafka.consumer.retry-attempts", () -> 3);
        registry.add("app.kafka.consumer.retry-backoff-millis", () -> 25);
        registry.add("app.kafka.engine-intelligence-recovery.dead-letter-topic", () -> EI_DLT);
        registry.add("app.kafka.engine-intelligence-recovery.redrive-topic", () -> EI_REDRIVE);
        registry.add("app.kafka.engine-intelligence-recovery.quarantine-topic", () -> EI_QUARANTINE);
        registry.add("app.kafka.engine-intelligence-recovery.redrive-group-id", () -> "ei-redrive." + SUFFIX);
        registry.add("app.kafka.engine-intelligence-recovery.source-consumer-group", () -> EI_SOURCE_GROUP);
        registry.add("app.kafka.engine-intelligence-recovery.redrive-enabled", () -> true);
        registry.add("app.engine-intelligence.pending-projection.enabled", () -> false);
        registry.add("app.engine-intelligence.pending-projection.batch-size", () -> 10);
        registry.add("app.engine-intelligence.pending-projection.max-attempts", () -> 10);
        registry.add("app.engine-intelligence.pending-projection.retry-delay", () -> "PT0.05S");
        registry.add("app.engine-intelligence.pending-projection.lease-duration", () -> "PT5S");
        registry.add("app.engine-intelligence.pending-projection.max-age", () -> "PT5M");
        registry.add("app.outbox.publisher.enabled", () -> false);
    }

    @Test
    void dedicatedRedriveSurvivesWorkerRestartAndCannotRepeatBaselineEffects() throws Exception {
        TransactionScoredEvent event = event(
                "engine-intelligence-recovery-event",
                "engine-intelligence-recovery-transaction",
                "engine-intelligence-recovery-correlation",
                Instant.parse("2026-10-04T19:00:00Z")
        );
        send(event);
        ConsumerRecord<String, byte[]> sourceRecord = awaitRecord(SOURCE_TOPIC, event.transactionId());
        await(() -> mongoTemplate.exists(
                Query.query(Criteria.where("_id").is(event.transactionId())),
                ScoredTransactionDocument.class
        ), "baseline occurrence was not persisted");
        newWorker().recoverPendingProjections();
        await(() -> mongoTemplate.exists(
                Query.query(Criteria.where("_id").is(event.transactionId())),
                EngineIntelligenceProjection.class
        ), "initial EI projection was not persisted");

        mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(event.transactionId())),
                EngineIntelligenceProjection.class
        );
        FraudCaseDocument fraudCaseBefore = baselineFraudCase(event);
        mongoTemplate.insert(fraudCaseBefore);
        Map<String, List<String>> baselineBefore = baselineSnapshot();

        publishDeadLetter(event.transactionId(), sourceRecord.value(), 1, 42L);
        ConsumerRecord<String, byte[]> deadLetter = awaitRecord(EI_DLT, event.transactionId());
        copyToRedrive(deadLetter);
        await(() -> mongoTemplate.exists(
                Query.query(Criteria.where("_id").is(event.eventId())),
                EngineIntelligencePendingProjection.class
        ), "redrive was not durably admitted");

        EngineIntelligencePendingProjection admitted = mongoTemplate.findById(
                event.eventId(),
                EngineIntelligencePendingProjection.class
        );
        assertThat(admitted.getRecoveryProvenance()).isEqualTo(new EngineIntelligenceRecoveryProvenance(
                EngineIntelligenceRecoveryProvenance.CONTRACT_VERSION,
                EI_DLT,
                SOURCE_TOPIC,
                1,
                42L,
                EI_SOURCE_GROUP
        ));

        newWorker().recoverPendingProjections();
        EngineIntelligencePendingProjection recovered = mongoTemplate.findById(
                event.eventId(),
                EngineIntelligencePendingProjection.class
        );
        assertThat(recovered)
                .as(
                        "redrive must complete, status=%s reason=%s",
                        recovered == null ? null : recovered.getStatus(),
                        recovered == null ? null : recovered.getLastReason()
                )
                .isNull();
        assertThat(mongoTemplate.findById(event.transactionId(), EngineIntelligenceProjection.class))
                .extracting(EngineIntelligenceProjection::getSourceEventId)
                .isEqualTo(event.eventId());
        assertThat(baselineSnapshot()).isEqualTo(baselineBefore);
        assertThat(mongoTemplate.findById(fraudCaseBefore.getCaseId(), FraudCaseDocument.class))
                .extracting(FraudCaseDocument::getUpdatedAt)
                .isEqualTo(fraudCaseBefore.getUpdatedAt());

        copyToRedrive(deadLetter);
        await(() -> mongoTemplate.exists(
                Query.query(Criteria.where("_id").is(event.eventId())),
                EngineIntelligencePendingProjection.class
        ), "duplicate redrive was not durably admitted");
        newWorker().recoverPendingProjections();

        assertThat(baselineSnapshot()).isEqualTo(baselineBefore);
        assertThat(countRecords(EI_DLT, event.transactionId())).isEqualTo(1L);
    }

    @Test
    void redriveWithoutOriginalProvenanceIsQuarantined() throws Exception {
        TransactionScoredEvent event = event(
                "engine-intelligence-invalid-recovery-event",
                "engine-intelligence-invalid-recovery-transaction",
                "engine-intelligence-invalid-recovery-correlation",
                Instant.parse("2026-10-04T19:05:00Z")
        );

        sendBytes(EI_REDRIVE, event.transactionId(), serialize(event), new RecordHeaders());

        assertThat(awaitRecord(EI_QUARANTINE, event.transactionId()).value()).isNotEmpty();
        assertThat(mongoTemplate.findById(event.eventId(), EngineIntelligencePendingProjection.class)).isNull();
    }

    @Test
    void durableDeferralSurvivesWorkerRestartUntilBaselineBecomesAuthoritative() throws Exception {
        Instant occurrenceCreatedAt = Instant.parse("2026-10-04T19:10:00.000000100Z");
        TransactionScoredEvent event = event(
                "engine-intelligence-pending-precision-event",
                "engine-intelligence-pending-precision-transaction",
                "engine-intelligence-pending-precision-correlation",
                occurrenceCreatedAt
        );
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(event.eventId())),
                EngineIntelligencePendingProjection.class);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(event.transactionId())),
                EngineIntelligenceProjection.class);
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(event.transactionId())),
                ScoredTransactionDocument.class);

        MessageListenerContainer baseline = listener("authoritativeTransactionScoredListener");
        baseline.pause();
        await(baseline::isContainerPaused, "baseline consumer did not pause");

        try {
            RecordMetadata sourceRecord = send(event);
            await(() -> mongoTemplate.exists(
                    Query.query(Criteria.where("_id").is(event.eventId())),
                    EngineIntelligencePendingProjection.class
            ), "EI consumer did not durably hand off the pending projection");
            awaitCommittedOffset(
                    "engine-intelligence.ei-pending." + SUFFIX,
                    new TopicPartition(sourceRecord.topic(), sourceRecord.partition()),
                    sourceRecord.offset() + 1L
            );
            LockSupport.parkNanos(Duration.ofMillis(150).toNanos());
            assertThat(countRecords(EI_DLT, event.transactionId())).isZero();

            newWorker().recoverPendingProjections();
            EngineIntelligencePendingProjection deferred = mongoTemplate.findById(
                    event.eventId(),
                    EngineIntelligencePendingProjection.class
            );
            assertThat(deferred).isNotNull();
            assertThat(deferred.getStatus()).isEqualTo(EngineIntelligencePendingProjectionStatus.PENDING);
            assertThat(deferred.getSourceEventCreatedAt()).isEqualTo(occurrenceCreatedAt);
            assertThat(deferred.getSourceEventCreatedAtText()).isEqualTo(occurrenceCreatedAt.toString());
            assertThat(deferred.getSourceEventCreatedAtEpochSecond())
                    .isEqualTo(occurrenceCreatedAt.getEpochSecond());
            assertThat(deferred.getSourceEventCreatedAtNano()).isEqualTo(occurrenceCreatedAt.getNano());

            baseline.resume();
            await(() -> mongoTemplate.exists(
                    Query.query(Criteria.where("_id").is(event.transactionId())),
                    ScoredTransactionDocument.class
            ), "baseline occurrence did not become authoritative");
            LockSupport.parkNanos(Duration.ofMillis(75).toNanos());

            newWorker().recoverPendingProjections();
            await(() -> mongoTemplate.exists(
                    Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(event.transactionId()),
                            Criteria.where("sourceEventId").is(event.eventId())
                    )),
                    EngineIntelligenceProjection.class
            ), "restarted EI worker did not project the authoritative occurrence");

            assertThat(mongoTemplate.findById(event.eventId(), EngineIntelligencePendingProjection.class)).isNull();
            assertThat(mongoTemplate.count(
                    Query.query(Criteria.where("transactionId").is(event.transactionId())),
                    AlertDocument.class
            )).isEqualTo(1L);
            assertThat(mongoTemplate.count(
                    Query.query(Criteria.where("_id").is(event.transactionId())),
                    ScoredTransactionDocument.class
            )).isEqualTo(1L);
            assertThat(countRecords(EI_DLT, event.transactionId())).isZero();
        } finally {
            baseline.resume();
        }
    }

    private EngineIntelligencePendingProjectionWorker newWorker() {
        return new EngineIntelligencePendingProjectionWorker(
                mongoTemplate,
                projectionService,
                pendingProperties,
                metrics,
                Clock.systemUTC()
        );
    }

    private MessageListenerContainer listener(String id) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(id);
        assertThat(container).as("Kafka listener " + id).isNotNull();
        return container;
    }

    private RecordMetadata send(TransactionScoredEvent event) throws Exception {
        return sendBytes(SOURCE_TOPIC, event.transactionId(), serialize(event), new RecordHeaders());
    }

    private RecordMetadata sendBytes(
            String topic,
            String key,
            byte[] value,
            RecordHeaders headers
    ) throws Exception {
        Map<String, Object> properties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"
        );
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(
                properties,
                new StringSerializer(),
                new ByteArraySerializer()
        )) {
            return producer.send(new ProducerRecord<>(topic, null, key, value, headers))
                    .get(10, TimeUnit.SECONDS);
        }
    }

    private byte[] serialize(TransactionScoredEvent event) {
        return new JacksonKafkaSerializer<TransactionScoredEvent>().serialize(SOURCE_TOPIC, event);
    }

    private void publishDeadLetter(String transactionId, byte[] sourcePayload, int sourcePartition, long sourceOffset)
            throws Exception {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, SOURCE_TOPIC.getBytes(StandardCharsets.UTF_8));
        headers.add(
                KafkaHeaders.DLT_ORIGINAL_PARTITION,
                ByteBuffer.allocate(Integer.BYTES).putInt(sourcePartition).array()
        );
        headers.add(
                KafkaHeaders.DLT_ORIGINAL_OFFSET,
                ByteBuffer.allocate(Long.BYTES).putLong(sourceOffset).array()
        );
        headers.add(
                KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP,
                EI_SOURCE_GROUP.getBytes(StandardCharsets.UTF_8)
        );
        sendBytes(EI_DLT, transactionId, sourcePayload, headers);
    }

    private void copyToRedrive(ConsumerRecord<String, byte[]> deadLetter) throws Exception {
        sendBytes(
                EI_REDRIVE,
                deadLetter.key(),
                deadLetter.value(),
                new RecordHeaders(deadLetter.headers())
        );
    }

    private ConsumerRecord<String, byte[]> awaitRecord(String topic, String key) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "ei-inspection." + UUID.randomUUID(),
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
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Kafka record was not available on " + topic);
    }

    private long countRecords(String topic, String key) {
        long count = 0L;
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "ei-retention-inspection." + UUID.randomUUID(),
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
            for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofSeconds(1))) {
                if (key.equals(record.key())) {
                    count++;
                }
            }
        }
        return count;
    }

    private Map<String, List<String>> baselineSnapshot() {
        return Map.of(
                "scored_transactions", documents("scored_transactions"),
                "alerts", documents("alerts"),
                "fraud_cases", documents("fraud_cases"),
                "suspicious_transactions", documents("suspicious_transactions"),
                "fraud_alert_outbox_records", documents("fraud_alert_outbox_records"),
                "ml_prediction_evidence_projections", documents("ml_prediction_evidence_projections"),
                "fraud_feedback_records", documents("fraud_feedback_records"),
                "engine_intelligence_feedback", documents("engine_intelligence_feedback")
        );
    }

    private List<String> documents(String collection) {
        List<Document> documents = mongoTemplate.getCollection(collection)
                .find()
                .into(new ArrayList<>());
        return documents.stream()
                .map(Document::toJson)
                .sorted()
                .toList();
    }

    private TransactionScoredEvent event(
            String eventId,
            String transactionId,
            String correlationId,
            Instant createdAt
    ) {
        TransactionScoredEvent source = EngineIntelligenceProjectionTestFixtures.event(
                EngineIntelligenceProjectionTestFixtures.minimalSummary()
        );
        return new TransactionScoredEvent(
                eventId,
                transactionId,
                correlationId,
                source.customerId(),
                source.accountId(),
                createdAt,
                source.transactionTimestamp(),
                source.transactionAmount(),
                source.merchantInfo(),
                source.deviceInfo(),
                source.locationInfo(),
                source.customerContext(),
                source.fraudScore(),
                source.riskLevel(),
                source.scoringStrategy(),
                source.modelName(),
                source.modelVersion(),
                source.inferenceTimestamp(),
                source.reasonCodes(),
                source.scoreDetails(),
                source.featureSnapshot(),
                source.alertRecommended(),
                source.scoringEvidence(),
                source.engineIntelligence(),
                source.mlPredictionEvidence(),
                source.analystRecommendation()
        );
    }

    private FraudCaseDocument baselineFraudCase(TransactionScoredEvent event) {
        FraudCaseDocument document = new FraudCaseDocument();
        document.setCaseId("engine-intelligence-baseline-case");
        document.setCaseKey("engine-intelligence-baseline-case-key");
        document.setCustomerId(event.customerId());
        document.setTransactionIds(List.of(event.transactionId()));
        document.setCreatedAt(event.createdAt());
        document.setUpdatedAt(event.createdAt());
        return document;
    }

    private void awaitCommittedOffset(String groupId, TopicPartition partition, long expectedOffset) {
        await(() -> {
            try (AdminClient admin = AdminClient.create(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    FraudPlatformContainers.kafka().getBootstrapServers()
            ))) {
                var committed = admin.listConsumerGroupOffsets(groupId)
                        .partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS)
                        .get(partition);
                return committed != null && committed.offset() >= expectedOffset;
            } catch (Exception exception) {
                return false;
            }
        }, "EI source offset was not committed after durable inbox handoff");
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
}
