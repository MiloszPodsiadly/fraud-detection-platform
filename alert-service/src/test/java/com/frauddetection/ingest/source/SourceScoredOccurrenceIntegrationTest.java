package com.frauddetection.ingest.source;

import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.alert.mapper.ScoredTransactionDocumentMapper;
import com.frauddetection.alert.persistence.ScoredTransactionDocument;
import com.frauddetection.alert.persistence.ScoredTransactionProjectionWriter;
import com.frauddetection.alert.persistence.ScoringOccurrenceFingerprint;
import com.frauddetection.common.events.contract.*;
import com.frauddetection.common.events.kafka.*;
import com.frauddetection.common.events.source.*;
import com.frauddetection.common.testsupport.fixture.TransactionFixtures;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.messaging.KafkaTransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import com.frauddetection.enricher.persistence.FeatureNamespace;
import com.frauddetection.enricher.service.*;
import com.frauddetection.ingest.controller.AttestedTransactionIngestController;
import com.frauddetection.ingest.exception.TransactionIngestExceptionHandler;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.frauddetection.ingest.messaging.KafkaTransactionRawEventProducer;
import com.frauddetection.ingest.observability.CorrelationIdProvider;
import com.frauddetection.scoring.config.*;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.messaging.KafkaTransactionScoredEventPublisher;
import com.frauddetection.scoring.messaging.TransactionScoredEventPublisher;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.*;
import com.frauddetection.scoring.service.*;
import com.frauddetection.scoring.source.SourceOccurrencePublisher;
import com.frauddetection.scoring.source.SourceOccurrenceConfiguration;
import com.frauddetection.scoring.context.ScoringContext;
import com.frauddetection.scoring.device.DeviceRiskSignalEngine;
import com.frauddetection.enricher.config.DeviceRiskAdmissionConfiguration;
import com.frauddetection.enricher.config.DeviceRiskAdmissionProperties;
import com.frauddetection.enricher.config.MerchantRiskAdmissionConfiguration;
import com.frauddetection.enricher.config.MerchantRiskAdmissionProperties;
import com.frauddetection.enricher.device.DisabledDeviceRiskAdmissionAdapter;
import com.frauddetection.enricher.merchant.MerchantRiskAdmission;
import com.frauddetection.common.events.merchant.MerchantRiskEvidenceClaim;
import com.mongodb.*;
import com.mongodb.client.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.frauddetection.enricher.persistence.RedisFeatureStore;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.lettuce.core.resource.DefaultClientResources;
import org.testcontainers.containers.GenericContainer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.frauddetection.alert.domain.ScoringOccurrenceAdmissionResult.Outcome.*;
import static com.frauddetection.common.events.source.MongoSourceOccurrenceStore.Status.*;
import static com.frauddetection.common.events.source.SourceLineage.State.VERIFIED;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual source ingress, Kafka hops, authenticated Mongo, current mapper and authoritative alert occurrence fence.
 * The feature-history fixture is deliberately empty; it grants no Device/Merchant history completeness.
 */
class SourceScoredOccurrenceIntegrationTest {
    private static final AuthenticatedSourceMongo MONGO = new AuthenticatedSourceMongo();
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final String ENCRYPTION_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final SourceOriginProofs ORIGIN_PROOFS = new SourceOriginProofs(new SourceOriginProperties(true,
            "origin-key-1", Map.of("origin-key-1", originProofKey())));
    private static final String RAW_ENCRYPTION_KEY = rawEncryptionKey();
    private static final String ENRICHED_ENCRYPTION_KEY = enrichedEncryptionKey();
    private static final SourceRawPublicationProperties RAW_PUBLICATION_PROPERTIES = new SourceRawPublicationProperties(
            true, "raw-key-1", Map.of("raw-key-1", RAW_ENCRYPTION_KEY), Duration.ofSeconds(1), Duration.ofSeconds(30), 10, 8);
    private static final byte[] PAYLOAD = payload();
    private static ValidatorFactory validation;
    private final List<DefaultKafkaProducerFactory<?, ?>> factories = new ArrayList<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AtomicInteger computations = new AtomicInteger();
    private MongoClient ownerClient;
    private MongoClient enricherClient;
    private MongoClient scorerClient;
    private MongoSourceReceiptStore receipts;
    private MongoSourceLineage owner;
    private MongoSourceLineage enricher;
    private MongoSourceLineage reader;
    private MongoSourceOccurrenceStore store;
    private SourceSignatureVerifier policy;
    private String database;
    private String rawTopic;
    private String enrichedTopic;
    private String scoredTopic;
    private KafkaTransactionScoredEventPublisher kafkaPublisher;
    private SourceOccurrencePublisher coordinator;
    private String lastIngestResponse;
    private ScoredTransactionProjectionWriter projection;
    private final ScoredTransactionDocumentMapper authoritativeMapper = new ScoredTransactionDocumentMapper();

    @BeforeAll static void start() throws Exception { MONGO.start(); KAFKA.start(); validation = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void stop() { if (validation != null) { validation.close(); } KAFKA.stop(); MONGO.close(); }
    @BeforeEach void setUp() throws Exception {
        database = "occurrence_" + UUID.randomUUID().toString().replace("-", "");
        rawTopic = database + ".raw"; enrichedTopic = database + ".enriched"; scoredTopic = database + ".scored";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(rawTopic, 1, (short) 1), new NewTopic(enrichedTopic, 1, (short) 1),
                    new NewTopic(scoredTopic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        ownerClient = MONGO.owner(); policy = policy(false);
        owner = new MongoSourceLineage(ownerClient, database, "source_owner", MongoSourceLineage.Stage.INGEST, policy, Clock.systemUTC());
        receipts = new MongoSourceReceiptStore(ownerClient, database, Clock.systemUTC(),
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), policy, owner,
                new SourceRawPublicationCipher("raw-key-1", RAW_PUBLICATION_PROPERTIES.encryptionKeys()), RAW_PUBLICATION_PROPERTIES);
        enricherClient = MONGO.consumer(ownerClient, database, true);
        scorerClient = MONGO.occurrenceWriter(ownerClient, database);
        enricher = new MongoSourceLineage(enricherClient, database, "enricher", MongoSourceLineage.Stage.ENRICHER, policy, Clock.systemUTC(),
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_ENCRYPTION_KEY)));
        reader = new MongoSourceLineage(scorerClient, database, "scorer", MongoSourceLineage.Stage.SCORER, policy, Clock.systemUTC());
        store = newStore(scorerClient, policy, Clock.systemUTC());
        kafkaPublisher = new KafkaTransactionScoredEventPublisher(template(), new KafkaTopicProperties(enrichedTopic, scoredTopic, database + ".dlt"));
        coordinator = new SourceOccurrencePublisher(store, kafkaPublisher);
        projection = new ScoredTransactionProjectionWriter(new MongoTemplate(ownerClient, database));
    }
    @AfterEach void cleanUp() { factories.forEach(DefaultKafkaProducerFactory::destroy); metrics.close(); ownerClient.getDatabase(database).drop(); }

    @Test void sourceTravelsThroughRealKafkaAndJoinsExactlyTheAuthoritativeScoredFact() throws Exception {
        var input = signedInput(Map.of());
        service(coordinator).score(input);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        var bound = store.lookup(scored, reader.enriched(input));
        assertThat(bound.state()).isEqualTo(VERIFIED);
        assertThat(bound.binding().source().sourceEventId()).isEqualTo("source-event-42");
        assertThat(bound.binding().scoredEventId()).isEqualTo(scored.eventId()).isNotEqualTo(input.eventId()).isNotEqualTo("source-event-42");
        assertThat(bound.binding().scoredCreatedAt()).isEqualTo(scored.createdAt());
        assertThat(bound.binding().scoredFingerprint()).isEqualTo(ScoringOccurrenceFingerprint.from(scored));
        assertThat(SourceScoredOccurrence.fingerprint(scored)).isEqualTo(ScoringOccurrenceFingerprint.from(scored));
        assertThat(projection.write(authoritativeMapper.toDocument(scored)).outcome()).isEqualTo(APPLIED_NEW);
        assertThat(projection.write(authoritativeMapper.toDocument(scored)).outcome()).isEqualTo(IDEMPOTENT_REPLAY);
        var document = new MongoTemplate(ownerClient, database).findById(scored.transactionId(), ScoredTransactionDocument.class);
        var occurrence = ScoringOccurrenceOwnership.fromPersistedIdentity(document.getSourceEventId(), document.getSourceEventCreatedAt(),
                document.getSourceEventCreatedAtEpochSecond(), document.getSourceEventCreatedAtNano(), document.getSourceEventFingerprint());
        assertThat(occurrence.sourceEventId()).isEqualTo(bound.binding().scoredEventId());
        assertThat(occurrence.sourceEventCreatedAt()).isEqualTo(bound.binding().scoredCreatedAt());
        assertThat(occurrence.sourceEventFingerprint()).isEqualTo(bound.binding().scoredFingerprint());
        assertThat(JSON.writeValueAsString(scored)).doesNotContain("source-event-42", "institution-a", "sourceLineage", TestSourceIssuer.ISSUER);
    }

    @Test void productionRedisFeatureStorePreservesExactLineageAndExistingDuplicateSuppression() throws Exception {
        try (var redis = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379)) {
            redis.start();
            var resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
            var connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                    LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(3)).build());
            try {
                connections.afterPropertiesSet(); connections.start();
                var redisTemplate = new StringRedisTemplate(connections); redisTemplate.afterPropertiesSet();
                var featureStore = new RedisFeatureStore(redisTemplate, new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7),
                        Duration.ofDays(8), Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
                long ingressEnrichmentStarted = System.nanoTime();
                var input = signedInput(Map.of(), featureStore);
                long ingressEnrichmentNanos = System.nanoTime() - ingressEnrichmentStarted;
                var raw = records(rawTopic, TransactionRawEvent.class).getFirst();
                assertThat(featureStore.hasRecordedTransaction(raw, FeatureNamespace.attested(reader.raw(raw)))).isTrue();
                new KafkaTransactionRawEventProducer(template(), new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic)).publish(raw);
                enrich(records(rawTopic, TransactionRawEvent.class).getLast(), featureStore);
                assertThat(records(enrichedTopic, TransactionEnrichedEvent.class)).hasSize(1);
                long scoringStarted = System.nanoTime();
                service(coordinator).score(input);
                long scoringNanos = System.nanoTime() - scoringStarted;
                service(coordinator).score(input);
                var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst();
                assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1); assertThat(computations).hasValue(1);
                assertThat(store.lookup(scored, reader.enriched(input)).state()).isEqualTo(VERIFIED);
                long projectionStarted = System.nanoTime();
                assertThat(projection.write(authoritativeMapper.toDocument(scored)).outcome()).isEqualTo(APPLIED_NEW);
                long projectionNanos = System.nanoTime() - projectionStarted;
                assertThat(projection.write(authoritativeMapper.toDocument(scored)).outcome()).isEqualTo(IDEMPOTENT_REPLAY);
                System.out.println("SOURCE_E2E_RUNTIME_MEASUREMENTS " + new Document("path", "attested_ingress_redis_enrichment_kafka_scoring_mongo_projection")
                        .append("harnessIngressThroughEnrichmentMillis", ingressEnrichmentNanos / 1_000_000d)
                        .append("scoringOccurrenceKafkaMongoMillis", scoringNanos / 1_000_000d)
                        .append("alertProjectionMongoMillis", projectionNanos / 1_000_000d)
                        .append("note", "single local Testcontainers sample; ingress/enrichment includes test-consumer round trips; diagnostic, not an SLO").toJson());
                try (var connection = connections.getConnection()) {
                    assertThat(connection.ping()).isEqualTo("PONG");
                    var limits = redis.getContainerInfo().getHostConfig();
                    System.out.println("SOURCE_E2E_ENVIRONMENT " + new Document("java", System.getProperty("java.version"))
                            .append("os", System.getProperty("os.name")).append("processors", Runtime.getRuntime().availableProcessors())
                            .append("maxHeapBytes", Runtime.getRuntime().maxMemory()).append("mongo", MONGO.resourceLimits())
                            .append("mongoVersion", ownerClient.getDatabase("admin").runCommand(new Document("buildInfo", 1)).getString("version"))
                            .append("kafkaImage", KAFKA.getDockerImageName()).append("kafkaMemoryBytes", KAFKA.getContainerInfo().getHostConfig().getMemory())
                            .append("kafkaNanoCpus", KAFKA.getContainerInfo().getHostConfig().getNanoCPUs())
                            .append("redisImage", redis.getDockerImageName()).append("redisVersion", connection.serverCommands().info("server").getProperty("redis_version"))
                            .append("redisMemoryBytes", limits.getMemory()).append("redisNanoCpus", limits.getNanoCPUs()).toJson());
                }
            } finally { connections.destroy(); resources.shutdown(0, 1, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS); }
        }
    }

    @Test void independentEnrichedRecoveryPublishesStoredSnapshotAfterProducerFailureWithoutRawRedelivery() throws Exception {
        try (var redis = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379)) {
            redis.start();
            var resources = DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
            var connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                    LettuceClientConfiguration.builder().clientResources(resources).commandTimeout(Duration.ofSeconds(3)).build());
            try {
                connections.afterPropertiesSet(); connections.start();
                var redisTemplate = new StringRedisTemplate(connections); redisTemplate.afterPropertiesSet();
                var redisStore = new RedisFeatureStore(redisTemplate, new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7),
                        Duration.ofDays(8), Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
                var snapshotReads = new AtomicInteger();
                FeatureStore counted = new FeatureStore() {
                    @Override public boolean hasRecordedTransaction(TransactionRawEvent event) { return redisStore.hasRecordedTransaction(event); }
                    @Override public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                        return redisStore.hasRecordedTransaction(event, namespace);
                    }
                    @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) { return redisStore.loadSnapshot(event); }
                    @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                        snapshotReads.incrementAndGet(); return redisStore.loadSnapshot(event, namespace);
                    }
                    @Override public void recordTransaction(TransactionRawEvent event) { redisStore.recordTransaction(event); }
                    @Override public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                        redisStore.recordTransaction(event, namespace);
                    }
                };
                var verified = policy.verify(TestSourceIssuer.proof(PAYLOAD, Map.of("sourceEventId", "enriched-recovery-event"), 0),
                        PAYLOAD, sourceContext());
                assertThat(verified).isInstanceOf(SourceSignatureVerifier.Verified.class);
                var request = JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class);
                TransactionRawEvent raw = new TransactionRequestMapper().toEvent(request, "enriched-recovery-correlation");
                var admitted = receipts.acceptRaw((SourceSignatureVerifier.Verified) verified, raw);
                assertThat(admitted.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);
                assertThat(receipts.recoverRawPublications(new KafkaTransactionRawEventProducer(template(),
                        new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic), ORIGIN_PROOFS), 1)).isEqualTo(1);
                assertThat(records(rawTopic, TransactionRawEvent.class)).hasSize(1);

                var failingPublisher = new com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher() {
                    @Override public void publish(TransactionEnrichedEvent event) {
                        throw new IllegalStateException("simulated Kafka outage");
                    }
                };
                var service = new TransactionFeatureEnricherService(counted,
                        new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                        failingPublisher, enricher, ORIGIN_PROOFS);
                assertThatThrownBy(() -> service.enrich(raw)).isInstanceOf(RuntimeException.class);
                assertThat(snapshotReads).hasValue(1);
                Document row = ownerClient.getDatabase(database).getCollection("source_enriched_publications").find().first();
                assertThat(row.getString("state")).isEqualTo("PENDING");
                assertThat(row.getInteger("attempts")).isEqualTo(1);
                TransactionEnrichedEvent stored = new SourceEnrichedPublicationCipher("enriched-key-1",
                        Map.of("enriched-key-1", ENRICHED_ENCRYPTION_KEY)).decrypt(row);

                // Change Redis history after the canonical enriched event is durable.
                redisStore.recordTransaction(raw, FeatureNamespace.attested(reader.raw(raw)));
                ownerClient.getDatabase(database).getCollection("source_enriched_publications").updateOne(eq("_id", row.getString("_id")),
                        set("nextAttemptAt", Date.from(Instant.EPOCH)));
                var recoveryPublisher = new KafkaTransactionEnrichedEventPublisher(template(),
                        new com.frauddetection.enricher.config.KafkaTopicProperties(rawTopic, enrichedTopic, database + ".dlt"), ORIGIN_PROOFS);
                new com.frauddetection.enricher.service.SourceEnrichedPublicationRecovery(enricher, recoveryPublisher,
                        new com.frauddetection.enricher.config.SourceEnrichedPublicationRecoveryProperties(true, 10, 0, 5_000)).recover();

                TransactionEnrichedEvent delivered = records(enrichedTopic, TransactionEnrichedEvent.class).getFirst();
                assertThat(delivered).isEqualTo(stored);
                assertThat(JSON.writeValueAsBytes(delivered)).containsExactly(JSON.writeValueAsBytes(stored));
                assertThat(snapshotReads).hasValue(1);
                assertThat(records(rawTopic, TransactionRawEvent.class)).hasSize(1);
                row = ownerClient.getDatabase(database).getCollection("source_enriched_publications")
                        .find(eq("_id", row.getString("_id"))).first();
                assertThat(row.getString("state")).isEqualTo("PUBLISHED");
                assertThat(row.getInteger("attempts")).isEqualTo(2);
                assertThat(enricher.enriched(delivered).state()).isEqualTo(VERIFIED);
            } finally {
                connections.destroy();
                resources.shutdown(0, 1, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void sourceProofAndPiiStayOutOfIngestResponsesApplicationLogsAndMetricTags() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.frauddetection");
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try {
            var input = signedInput(Map.of()); service(coordinator).score(input);
            var proof = Base64.getUrlEncoder().withoutPadding().encodeToString(TestSourceIssuer.proof(PAYLOAD, Map.of(), 0));
            var ingress = new AttestedTransactionIngestService(new TransactionRequestMapper(), policy, receipts,
                    event -> fail("invalid proof must not publish"), new CorrelationIdProvider(),
                    new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic), validation.getValidator());
            var rejected = MockMvcBuilders.standaloneSetup(new AttestedTransactionIngestController(ingress))
                    .setControllerAdvice(new TransactionIngestExceptionHandler()).build()
                    .perform(post("/api/v1/transactions/attested").contentType("application/json").content(PAYLOAD)
                            .header("X-Source-Proof", "private-proof-canary-invalid"))
                    .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
            String logs = captured.list.stream().map(event -> event.getFormattedMessage() + event.getKeyValuePairs()
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage())).collect(java.util.stream.Collectors.joining("\n"));
            String tags = metrics.getMeters().stream().map(meter -> meter.getId().toString()).collect(java.util.stream.Collectors.joining("\n"));
            for (String output : List.of(lastIngestResponse, rejected, logs, tags)) {
                assertThat(output).doesNotContain(proof, ENCRYPTION_KEY, TestSourceIssuer.publicKey(0), TestSourceIssuer.ISSUER,
                        "institution-a", "source-event-42", "private-proof-canary-invalid", "Northwind Electronics", "Mozilla/5.0", "203.0.113.24", "example.com");
            }
        } finally { logger.detachAppender(captured); captured.stop(); }
    }

    @Test void alteredCiphertextCannotReuseAnOtherwiseMatchingPublishedReference() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        var event = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        var row = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first();
        byte[] altered = row.get("ciphertext", Binary.class).getData().clone(); altered[0] ^= 1;
        ownerClient.getDatabase(database).getCollection("source_scored_occurrences").updateOne(eq("_id", row.getString("_id")), set("ciphertext", new Binary(altered)));
        assertThat(store.readOnly(sourceContext()).find(reader.enriched(input), reference(event))).isEmpty();
        assertThat(store.resume(input, "initial").status()).isEqualTo(UNAVAILABLE);
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source_lineage_raw", "source_lineage_enriched", "source_scored_occurrences"})
    void missingBudgetIndexStopsNewAllocationAndStartupWithoutRepair(String collection) throws Exception {
        var input = signedInput(Map.of());
        String index = collection.equals("source_scored_occurrences") ? "scored_source_runs" : "lineage_source_links";
        ownerClient.getDatabase(database).getCollection(collection).dropIndex(index);
        assertThatThrownBy(() -> newStore(scorerClient, policy, Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
        assertThat(ownerClient.getDatabase(database).getCollection(collection).listIndexes().into(new ArrayList<>()))
                .noneMatch(row -> index.equals(row.getString("name")));
        if (collection.equals("source_scored_occurrences")) {
            assertThat(store.prepare(input, "initial", candidate(input)).status()).isEqualTo(UNAVAILABLE);
            assertThat(ownerClient.getDatabase(database).getCollection(collection).countDocuments()).isZero();
        } else if (collection.equals("source_lineage_raw")) {
            var raw = new TransactionRequestMapper().toEvent(JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class), "retry");
            var proof = (SourceSignatureVerifier.Verified) policy.verify(TestSourceIssuer.proof(PAYLOAD, Map.of(), 0), PAYLOAD, sourceContext());
            assertThat(owner.bindRaw(proof, reader.enriched(input).receiptId(), raw).state()).isNotEqualTo(VERIFIED);
            assertThat(ownerClient.getDatabase(database).getCollection(collection).countDocuments()).isEqualTo(1);
        } else {
            enrich(records(rawTopic, TransactionRawEvent.class).getFirst());
            assertThat(reader.enriched(records(enrichedTopic, TransactionEnrichedEvent.class).getLast()).state()).isEqualTo(VERIFIED);
            assertThat(ownerClient.getDatabase(database).getCollection(collection).countDocuments()).isEqualTo(1);
        }
    }

    @Test void measuresExactReplayAndPersistedEvidenceSizesWithoutRecomputingOrGrowingTheQueue() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        for (int index = 0; index < 5; index++) { assertThat(store.resume(input, "initial").status()).isEqualTo(PUBLISHED); }
        long[] samples = new long[30];
        for (int index = 0; index < samples.length; index++) {
            long started = System.nanoTime(); var replay = store.resume(input, "initial"); samples[index] = System.nanoTime() - started;
            assertThat(replay.status()).isEqualTo(PUBLISHED);
        }
        Arrays.sort(samples);
        int recoveryBatchSize = 8;
        String measuredReceiptId = null;
        var request = JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class);
        var rawMapper = new TransactionRequestMapper();
        for (int index = 0; index < recoveryBatchSize; index++) {
            var evidenceResult = policy.verify(TestSourceIssuer.proof(PAYLOAD,
                    Map.of("sourceEventId", "recovery-measure-" + index), 0), PAYLOAD, sourceContext());
            assertThat(evidenceResult).isInstanceOf(SourceSignatureVerifier.Verified.class);
            var event = rawMapper.toEvent(request, "recovery-measure-correlation-" + index);
            var admitted = receipts.acceptRaw((SourceSignatureVerifier.Verified) evidenceResult, event);
            assertThat(admitted.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);
            if (measuredReceiptId == null) { measuredReceiptId = admitted.receipt().id(); }
        }
        long recoveryStarted = System.nanoTime();
        int recovered = receipts.recoverRawPublications(new KafkaTransactionRawEventProducer(template(),
                new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic), ORIGIN_PROOFS), recoveryBatchSize);
        long recoveryNanos = System.nanoTime() - recoveryStarted;
        assertThat(recovered).isEqualTo(recoveryBatchSize);
        var sizes = new Document();
        for (String collection : List.of("source_admission_receipts", "source_lineage_raw", "source_lineage_enriched", "source_scored_occurrences")) {
            var row = ownerClient.getDatabase(database).getCollection(collection).find().first();
            try (var output = new org.bson.io.BasicOutputBuffer(); var writer = new org.bson.BsonBinaryWriter(output)) {
                new org.bson.codecs.DocumentCodec().encode(writer, row, org.bson.codecs.EncoderContext.builder().isEncodingCollectibleDocument(true).build());
                sizes.append(collection, output.getSize());
            }
        }
        var rawOutbox = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", measuredReceiptId)).first();
        try (var output = new org.bson.io.BasicOutputBuffer(); var writer = new org.bson.BsonBinaryWriter(output)) {
            new org.bson.codecs.DocumentCodec().encode(writer, rawOutbox,
                    org.bson.codecs.EncoderContext.builder().isEncodingCollectibleDocument(true).build());
            sizes.append(MongoSourceRawPublicationSchema.COLLECTION, output.getSize());
        }
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isEqualTo(1);
        assertThat(store.pending(100)).isEmpty(); assertThat(computations).hasValue(1);
        System.out.println("SOURCE_REPLAY_MEASUREMENTS " + new Document("samples", samples.length).append("warmup", 5)
                .append("p50Millis", samples[14] / 1_000_000d).append("p95Millis", samples[28] / 1_000_000d).append("bsonBytes", sizes).toJson());
        System.out.println("SOURCE_RAW_RECOVERY_MEASUREMENTS " + new Document("pending", recoveryBatchSize).append("recovered", recovered)
                .append("elapsedMillis", recoveryNanos / 1_000_000d)
                .append("eventsPerSecond", recovered / (recoveryNanos / 1_000_000_000d))
                .append("broker", "Kafka Testcontainers; actual acknowledged publish").toJson());
    }

    @Test void duplicateRawDeliveryReusesCanonicalEnrichedOccurrenceAndChangedInterpretationsConflict() throws Exception {
        signedInput(Map.of());
        var raw = records(rawTopic, TransactionRawEvent.class).getFirst();
        var first = records(enrichedTopic, TransactionEnrichedEvent.class).getFirst();
        enrich(raw);
        var second = records(enrichedTopic, TransactionEnrichedEvent.class).getLast();
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(SourceEventCommitment.enriched(second)).isEqualTo(SourceEventCommitment.enriched(first));
        assertThat(records(enrichedTopic, TransactionEnrichedEvent.class)).hasSize(1);

        var changedFeatures = new HashMap<>(first.featureSnapshot());
        changedFeatures.put(com.frauddetection.common.events.features.FraudFeatureContract.MERCHANT_FREQUENCY_7D, 999);
        changedFeatures.put(com.frauddetection.common.events.features.FraudFeatureContract.DEVICE_NOVELTY,
                !Boolean.TRUE.equals(first.featureSnapshot().get(com.frauddetection.common.events.features.FraudFeatureContract.DEVICE_NOVELTY)));
        changedFeatures.put(com.frauddetection.common.events.features.FraudFeatureContract.TRANSACTION_VELOCITY_PER_MINUTE, 99.5d);
        var changed = change(first, "featureSnapshot", changedFeatures, TransactionEnrichedEvent.class);
        var differentUuid = change(first, "eventId", "another-enriched-uuid", TransactionEnrichedEvent.class);
        var changedTransaction = change(first, "customerId", "another-customer", TransactionEnrichedEvent.class);
        var originalCandidate = candidate(first);
        assertThat(store.resume(changed, "initial").status()).isEqualTo(CONFLICT);
        assertThat(store.prepare(changed, "initial", originalCandidate).status()).isEqualTo(CONFLICT);
        assertThat(store.resume(differentUuid, "initial").status()).isEqualTo(UNVERIFIED);
        assertThat(store.prepare(differentUuid, "initial", originalCandidate).status()).isEqualTo(UNVERIFIED);
        assertThat(store.resume(changedTransaction, "initial").status()).isEqualTo(CONFLICT);
        assertThat(store.prepare(changedTransaction, "initial", originalCandidate).status()).isEqualTo(CONFLICT);

        var service = service(coordinator); service.score(first); service.score(first);
        assertThat(computations).hasValue(1);
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1);
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isEqualTo(1);
    }

    @Test void explicitRescoreHasAnotherExactOccurrenceAndSameRunIdRemainsAReplay() throws Exception {
        var input = signedInput(Map.of()); var service = service(coordinator);
        service.score(input); service.rescore(input, "rescore:review-1"); service.rescore(input, "rescore:review-1");
        var events = records(scoredTopic, TransactionScoredEvent.class);
        assertThat(events).hasSize(2); assertThat(computations).hasValue(2);
        assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
        for (var event : events) { assertThat(store.lookup(event, reader.enriched(input)).state()).isEqualTo(VERIFIED); }
        projection.write(authoritativeMapper.toDocument(events.getFirst()));
        assertThat(projection.write(authoritativeMapper.toDocument(events.getLast())).outcome()).isEqualTo(APPLIED_NEWER);
        assertThat(store.lookup(events.getFirst(), reader.enriched(input)).state()).isEqualTo(VERIFIED); // no current-winner join.
        assertThatThrownBy(() -> service.rescore(input, "initial")).hasMessage("SOURCE_RESCORE_RUN_REQUIRED");
    }

    @Test void identicalBusinessIdsFromTwoSourcesNeverCrossBind() throws Exception {
        var original = TestSourceIssuer.policy(0, false);
        var other = new SourceVerificationProperties.TrustedKey("second-test-issuer", "institution-b", original.source(), original.sourceEpoch(),
                original.signingKeyVersion(), original.algorithm(), original.publicKeyBase64(), original.notBefore(), original.notAfter(), false,
                original.purpose(), original.audience(), original.policyVersion());
        configurePolicies(original, other);
        var first = signedInput(Map.of());
        var second = signedInput(Map.of("sourceEventId", "other-source-event", "issuer", other.issuer(), "institution", other.institution()));
        service(coordinator).score(first); service(coordinator).score(second);
        var events = records(scoredTopic, TransactionScoredEvent.class);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).transactionId()).isEqualTo(events.get(1).transactionId());
        assertThat(store.lookup(events.get(0), reader.enriched(first)).binding().source().sourceEventId()).isEqualTo("source-event-42");
        assertThat(store.lookup(events.get(1), reader.enriched(second)).binding().source().sourceEventId()).isEqualTo("other-source-event");
        assertThat(store.lookup(events.get(1), reader.enriched(second)).binding().source().institution()).isEqualTo("institution-b");
        assertThat(store.lookup(events.get(0), reader.enriched(second)).state()).isNotEqualTo(VERIFIED);
        assertThat(store.lookup(events.get(1), reader.enriched(first)).state()).isNotEqualTo(VERIFIED);
    }

    @Test void consumerViewReturnsOnlyCurrentPurposeBoundExactSourceEvidenceWithoutWriting() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        var authorization = reader.enriched(input);
        var reference = reference(scored);
        var before = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first();
        SourceOccurrenceAccess access = store.readOnly(sourceContext());
        var verified = access.find(authorization, reference).orElseThrow();
        assertThat(verified.source()).isEqualTo(authorization.source());
        assertThat(verified.source().purpose()).isEqualTo(AttestedTransactionIngestService.PURPOSE);
        assertThat(verified.source().audience()).isEqualTo(AttestedTransactionIngestService.AUDIENCE);
        assertThat(verified.scoredEventId()).isEqualTo(scored.eventId());
        assertThat(verified.scoredCreatedAt()).isEqualTo(scored.createdAt());
        assertThat(verified.scoredFingerprint()).isEqualTo(ScoringOccurrenceFingerprint.from(scored));
        assertThat(access.find(null, reference)).isEmpty();
        assertThat(access.find(SourceLineage.unavailable(SourceLineage.State.UNVERIFIED), reference)).isEmpty();
        assertThat(access.find(authorization, null)).isEmpty();
        assertThat(SourceOccurrenceAccess.disabled().find(authorization, reference)).isEmpty();
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first()).isEqualTo(before);
    }

    @Test void consumerViewRejectsWrongPurposeAudienceAndEveryPartOfTheOccurrenceReference() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst(); var authorization = reader.enriched(input);
        assertThat(store.readOnly(new SourceSignatureVerifier.Context("DEVICE_HISTORY_READ", AttestedTransactionIngestService.AUDIENCE))
                .find(authorization, reference(scored))).isEmpty();
        assertThat(store.readOnly(new SourceSignatureVerifier.Context(AttestedTransactionIngestService.PURPOSE, "merchant.runtime"))
                .find(authorization, reference(scored))).isEmpty();
        var access = store.readOnly(sourceContext());
        for (var reference : List.of(new SourceOccurrenceAccess.Reference("another-scored-fact", scored.createdAt(), ScoringOccurrenceFingerprint.from(scored)),
                new SourceOccurrenceAccess.Reference(scored.eventId(), scored.createdAt().plusNanos(1), ScoringOccurrenceFingerprint.from(scored)),
                new SourceOccurrenceAccess.Reference(scored.eventId(), scored.createdAt(), "0".repeat(64)))) {
            assertThat(access.find(authorization, reference)).isEmpty();
        }
    }

    @Test void consumerViewNeverJoinsMatchingDeviceMerchantAndBusinessIdsAcrossSourceScopes() throws Exception {
        var a = TestSourceIssuer.policy(0, false);
        var variants = List.of(
                Map.<String, Object>of("issuer", "institution-b-issuer", "institution", "institution-b"),
                Map.<String, Object>of("issuer", "another-issuer"),
                Map.<String, Object>of("source", "source-b", "signingKeyVersion", "source-b-key"),
                Map.<String, Object>of("sourceEpoch", "epoch-b"));
        var policies = new ArrayList<SourceVerificationProperties.TrustedKey>(); policies.add(a);
        for (var variant : variants) {
            policies.add(new SourceVerificationProperties.TrustedKey((String) variant.getOrDefault("issuer", a.issuer()),
                    (String) variant.getOrDefault("institution", a.institution()), (String) variant.getOrDefault("source", a.source()),
                    (String) variant.getOrDefault("sourceEpoch", a.sourceEpoch()), (String) variant.getOrDefault("signingKeyVersion", a.signingKeyVersion()),
                    a.algorithm(), a.publicKeyBase64(), a.notBefore(), a.notAfter(), false, a.purpose(), a.audience(), a.policyVersion()));
        }
        configurePolicies(policies.toArray(SourceVerificationProperties.TrustedKey[]::new));
        var first = signedInput(Map.of()); service(coordinator).score(first);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst(); var access = store.readOnly(sourceContext());
        for (var variant : variants) {
            var other = signedInput(variant);
            assertThat(other.transactionId()).isEqualTo(first.transactionId());
            assertThat(other.deviceInfo().deviceId()).isEqualTo(first.deviceInfo().deviceId());
            assertThat(other.merchantInfo().merchantId()).isEqualTo(first.merchantInfo().merchantId());
            assertThat(reader.enriched(other).state()).isEqualTo(VERIFIED);
            assertThat(access.find(reader.enriched(other), reference(scored))).isEmpty();
        }
        assertThat(access.find(reader.enriched(first), reference(scored))).isPresent();
    }

    @Test void consumerViewCannotUsePendingRevokedExpiredOrMissingEvidence() throws Exception {
        var input = signedInput(Map.of()); var authorization = reader.enriched(input);
        var prepared = store.prepare(input, "initial", candidate(input)); var reference = reference(prepared.event());
        var access = store.readOnly(sourceContext());
        assertThat(access.find(authorization, reference)).isEmpty();
        assertThat(coordinator.recoverPending(1)).isEqualTo(1);
        assertThat(access.find(authorization, reference)).isPresent();
        assertThat(newStore(scorerClient, policy(true), Clock.systemUTC()).readOnly(sourceContext()).find(authorization, reference)).isEmpty();
        assertThat(newStore(scorerClient, policy, Clock.offset(Clock.systemUTC(), Duration.ofHours(3))).readOnly(sourceContext())
                .find(authorization, reference)).isEmpty();
        ownerClient.getDatabase(database).getCollection("source_admission_identities").updateOne(eq("_id", authorization.receiptId()), set("state", "CONFLICT"));
        assertThat(access.find(authorization, reference)).isEmpty();
        ownerClient.getDatabase(database).getCollection("source_admission_identities").updateOne(eq("_id", authorization.receiptId()), set("state", "ADMITTED"));
        ownerClient.getDatabase(database).getCollection("source_admission_receipts").deleteOne(eq("_id", authorization.receiptId()));
        assertThat(access.find(authorization, reference)).isEmpty();
    }

    @Test void receiptVerificationOutsideTheKeyEpochCannotAuthorizeAPublishedOccurrence() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        var authorization = reader.enriched(input);
        var event = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        var access = store.readOnly(sourceContext());
        assertThat(access.find(authorization, reference(event))).isPresent();
        ownerClient.getDatabase(database).getCollection("source_admission_receipts").updateOne(eq("_id", authorization.receiptId()),
                set("verifiedAt", TestSourceIssuer.NOW.minusSeconds(7_200).toString()));
        assertThat(reader.enriched(input).state()).isEqualTo(SourceLineage.State.REVOKED);
        assertThat(access.find(authorization, reference(event))).isEmpty();
        assertThat(store.resume(input, "initial").event()).isNull();
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1);
    }

    @Test void verifiedSourceEvidenceDoesNotActivateDomainAdmissionEnginesOrHistoryWrites() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst(); var authorization = reader.enriched(input);
        var access = store.readOnly(sourceContext()); assertThat(access.find(authorization, reference(scored))).isPresent();
        var raw = records(rawTopic, TransactionRawEvent.class).getFirst();
        var runner = new ApplicationContextRunner().withUserConfiguration(DeviceRiskAdmissionConfiguration.class,
                        MerchantRiskAdmissionConfiguration.class, EngineIntelligenceRuntimeConfig.class)
                .withBean(SourceOccurrenceAccess.class, () -> access);
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(MongoClient.class);
            assertThat(context.getBean(DisabledDeviceRiskAdmissionAdapter.class).admit(raw).reasonCode())
                    .isEqualTo(DisabledDeviceRiskAdmissionAdapter.DENIAL_CODE);
            assertThat(context.getBean(MerchantRiskAdmission.class).admit(merchantClaim(input)))
                    .isEqualTo(MerchantRiskAdmission.Denial.MERCHANT_RISK_ADMISSION_DISABLED_NO_TRUSTED_ISSUER);
        });
        for (String property : List.of(DeviceRiskAdmissionProperties.PROPERTY_NAME, MerchantRiskAdmissionProperties.PROPERTY_NAME,
                DeviceRiskEngineProperties.PROPERTY_NAME, MerchantRiskEngineProperties.PROPERTY_NAME)) {
            runner.withPropertyValues(property + "=true").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(property);
            });
        }
        var result = new DeviceRiskSignalEngine().evaluate(new ScoringContext(input, input.featureSnapshot(), ScoringMode.RULE_BASED,
                input.correlationId(), Instant.now(), authorization));
        assertThat(result.status()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE);
        assertThat(result.score()).isNull(); assertThat(result.riskLevel()).isNull();
        assertThat(ownerClient.getDatabase(database).listCollectionNames().into(new ArrayList<>()))
                .noneMatch(name -> name.startsWith("device_") || name.startsWith("merchant_"));
    }

    @Test void lostKafkaAckRemainsUnavailableUntilRestartReplaysExactlyTheSameFact() throws Exception {
        var input = signedInput(Map.of());
        var lostAck = new SourceOccurrencePublisher(store, event -> { kafkaPublisher.publish(event); throw new IllegalStateException("test ack lost"); });
        assertThatThrownBy(() -> service(lostAck).score(input)).hasMessage("SOURCE_SCORED_PUBLICATION_UNRESOLVED");
        var first = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        assertThat(store.lookup(first, reader.enriched(input)).state()).isNotEqualTo(VERIFIED);
        scorerClient.close(); scorerClient = MONGO.reconnectScorer(database);
        reader = new MongoSourceLineage(scorerClient, database, "scorer", MongoSourceLineage.Stage.SCORER, policy, Clock.systemUTC());
        store = newStore(scorerClient, policy, Clock.systemUTC());
        ownerClient.getDatabase(database).getCollection("source_scored_occurrences")
                .updateOne(eq("state", "PENDING"), set("nextAttemptAt", Date.from(Instant.now().minusSeconds(1))));
        assertThat(new SourceOccurrencePublisher(store, kafkaPublisher).recoverPending(10)).isEqualTo(1);
        var events = records(scoredTopic, TransactionScoredEvent.class);
        assertThat(events).hasSize(2);
        assertThat(ScoringOccurrenceFingerprint.from(events.get(0))).isEqualTo(ScoringOccurrenceFingerprint.from(events.get(1)));
        assertThat(projection.write(authoritativeMapper.toDocument(events.get(0))).outcome()).isEqualTo(APPLIED_NEW);
        assertThat(projection.write(authoritativeMapper.toDocument(events.get(1))).outcome()).isEqualTo(IDEMPOTENT_REPLAY);
        assertThat(store.lookup(first, reader.enriched(input)).state()).isEqualTo(VERIFIED);
        assertThat(computations).hasValue(1);
    }

    @Test void unknownMongoPrepareCommitNeverPublishesAndReconcilesFromDurableFacts() throws Exception {
        var input = signedInput(Map.of());
        var faulty = newStore(commitFault(scorerClient, true), policy, Clock.systemUTC());
        var result = faulty.prepare(input, "initial", candidate(input));
        assertThat(result.status()).isEqualTo(UNAVAILABLE);
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).isEmpty();
        assertThat(store.resume(input, "initial").status()).isEqualTo(PENDING);
        assertThat(coordinator.recoverPending(1)).isEqualTo(1);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        assertThat(store.lookup(scored, reader.enriched(input)).state()).isEqualTo(VERIFIED);
    }

    @Test void unknownCommitBeforeDurableWriteHasNoAcceptedEffectAndCanBeRetriedExplicitly() throws Exception {
        var input = signedInput(Map.of());
        var faulty = newStore(commitFault(scorerClient, false), policy, Clock.systemUTC());
        assertThat(faulty.prepare(input, "initial", candidate(input)).status()).isEqualTo(UNAVAILABLE);
        assertThat(store.resume(input, "initial").status()).isEqualTo(ABSENT);
        service(coordinator).score(input);
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1);
    }

    @Test void unknownConfirmationCommitReturnsUnresolvedAndKnownDurableAckAvoidsAnotherPublication() throws Exception {
        var input = signedInput(Map.of());
        var prepared = store.prepare(input, "initial", candidate(input));
        var claim = store.claim(prepared.id()); kafkaPublisher.publish(claim.event());
        var faulty = newStore(commitFault(scorerClient, true), policy, Clock.systemUTC());
        assertThat(faulty.confirm(prepared.id(), claim.event(), claim.claimId()).status()).isEqualTo(UNAVAILABLE);
        assertThat(store.resume(input, "initial").status()).isEqualTo(PUBLISHED);
        service(coordinator).score(input);
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1);
        assertThat(computations).hasValue(0);
    }

    @Test void parallelInitialScoringReconcilesBoundedContentionIntoOneAuthoritativeEffect() throws Exception {
        var input = signedInput(Map.of());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 4; i++) {
                tasks.add(pool.submit(() -> {
                    try { service(coordinator).score(input); return true; }
                    catch (IllegalStateException unresolved) {
                        assertThat(unresolved).hasMessage("SOURCE_SCORED_PUBLICATION_UNRESOLVED");
                        return false; // bounded contention is an explicit retry result, never accepted success.
                    }
                }));
            }
            for (var task : tasks) { task.get(20, TimeUnit.SECONDS); }
        }
        // Reconcile explicitly after competitors leave. The initial run key reuses the durable winner.
        service(coordinator).score(input);
        var events = records(scoredTopic, TransactionScoredEvent.class);
        assertThat(events).isNotEmpty();
        assertThat(events.stream().map(ScoringOccurrenceFingerprint::from).distinct()).hasSize(1);
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isEqualTo(1);
        int applied = 0;
        for (var event : events) { if (projection.write(authoritativeMapper.toDocument(event)).outcome() == APPLIED_NEW) { applied++; } }
        assertThat(applied).isEqualTo(1);
    }

    @Test void realProcessDeathAfterRecordBeforePublishRecoversWithoutChangingTheScoredFact() throws Exception {
        var input = signedInput(Map.of()); var original = candidate(input);
        Path arguments = Files.createTempFile("source-occurrence-child-", ".args");
        Path output = Files.createTempFile("source-occurrence-child-", ".log");
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).replace('\\', '/');
            Files.writeString(arguments, "--class-path\n\"" + classpath + "\"\n" + CrashAfterPrepare.class.getName() + "\n");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(JSON.writeValueAsBytes(Map.of("uri", MONGO.scorerUri(database), "database", database,
                        "input", input, "candidate", original)));
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("source occurrence crash fixture timed out"); }
            assertThat(process.exitValue()).as("child process: %s", Files.readString(output)).isEqualTo(17);
        } finally { Files.deleteIfExists(arguments); Files.deleteIfExists(output); }
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).isEmpty();
        assertThat(store.resume(input, "initial").status()).isEqualTo(PENDING);
        scorerClient.close(); scorerClient = MONGO.reconnectScorer(database);
        reader = new MongoSourceLineage(scorerClient, database, "scorer", MongoSourceLineage.Stage.SCORER, policy, Clock.systemUTC());
        store = newStore(scorerClient, policy, Clock.systemUTC());
        assertThat(new SourceOccurrencePublisher(store, kafkaPublisher).recoverPending(1)).isEqualTo(1);
        var recovered = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        assertThat(ScoringOccurrenceFingerprint.from(recovered)).isEqualTo(ScoringOccurrenceFingerprint.from(original));
        assertThat(projection.write(authoritativeMapper.toDocument(recovered)).outcome()).isEqualTo(APPLIED_NEW);
        assertThat(store.lookup(recovered, reader.enriched(input)).state()).isEqualTo(VERIFIED);
    }

    @Test void occurrenceStorageCannotBeReadOrWrittenByTheWrongServiceAndRunCapacityIsBounded() throws Exception {
        assertThatThrownBy(() -> newStore(enricherClient, policy, Clock.systemUTC())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> newStore(ownerClient, policy, Clock.systemUTC())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> enricherClient.getDatabase(database).getCollection("source_scored_occurrences").find().first())
                .isInstanceOf(MongoCommandException.class);
        assertThatThrownBy(() -> scorerClient.getDatabase(database).getCollection("source_admission_receipts").deleteMany(new Document()))
                .isInstanceOf(MongoCommandException.class);
        var input = signedInput(Map.of());
        for (int run = 0; run < 32; run++) { assertThat(store.prepare(input, "rescore:fixture-" + run, candidate(input)).status()).isEqualTo(PENDING); }
        assertThat(store.prepare(input, "rescore:over-cap", candidate(input)).status()).isEqualTo(UNAVAILABLE);
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isEqualTo(32);
    }

    @Test void lostOccurrenceCounterCannotReopenTheRunBudget() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        ownerClient.getDatabase(database).getCollection("source_scored_occurrence_counts").deleteMany(new Document());
        assertThat(store.prepare(input, "rescore:after-counter-loss", candidate(input)).status()).isEqualTo(UNAVAILABLE);
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrence_counts").countDocuments()).isZero();
    }

    @Test void understatedOccurrenceCounterCannotReopenTheRunBudget() throws Exception {
        var input = signedInput(Map.of());
        assertThat(store.prepare(input, "initial", candidate(input)).status()).isEqualTo(PENDING);
        assertThat(store.prepare(input, "rescore:second", candidate(input)).status()).isEqualTo(PENDING);
        ownerClient.getDatabase(database).getCollection("source_scored_occurrence_counts").updateOne(new Document(), set("count", 1));
        assertThat(store.prepare(input, "rescore:after-corruption", candidate(input)).status()).isEqualTo(UNAVAILABLE);
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isEqualTo(2);
    }

    @Test void lostRawLinkCounterCannotReopenTheReceiverBudget() throws Exception {
        var input = signedInput(Map.of()); var authorization = reader.enriched(input);
        ownerClient.getDatabase(database).getCollection("source_lineage_raw_counts").deleteMany(new Document());
        var request = JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class);
        var raw = new TransactionRequestMapper().toEvent(request, "retry-after-counter-loss");
        var verified = (SourceSignatureVerifier.Verified) policy.verify(TestSourceIssuer.proof(PAYLOAD, Map.of(), 0), PAYLOAD, sourceContext());
        assertThat(owner.bindRaw(verified, authorization.receiptId(), raw).state()).isNotEqualTo(VERIFIED);
        assertThat(ownerClient.getDatabase(database).getCollection("source_lineage_raw").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(database).getCollection("source_lineage_raw_counts").countDocuments()).isZero();
    }

    @Test void lostEnrichedLinkCounterCannotReopenTheReceiverBudget() throws Exception {
        signedInput(Map.of()); var raw = records(rawTopic, TransactionRawEvent.class).getFirst();
        ownerClient.getDatabase(database).getCollection("source_lineage_enriched_counts").deleteMany(new Document());
        var features = new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false));
        var enriched = new TransactionEnrichedEventMapper().toEvent(raw, features);
        assertThat(enricher.bindEnriched(raw, enriched).state()).isNotEqualTo(VERIFIED);
        assertThat(ownerClient.getDatabase(database).getCollection("source_lineage_enriched").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(database).getCollection("source_lineage_enriched_counts").countDocuments()).isZero();
    }

    @Test void changedScoredBytesForgedLinkAndRevokedPolicyNeverProduceAnExactSourceGrant() throws Exception {
        var input = signedInput(Map.of()); service(coordinator).score(input);
        var scored = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        var changed = change(scored, "fraudScore", 0.99d, TransactionScoredEvent.class);
        assertThat(store.lookup(changed, reader.enriched(input)).state()).isNotEqualTo(VERIFIED);
        projection.write(authoritativeMapper.toDocument(scored));
        assertThat(projection.write(authoritativeMapper.toDocument(changed)).outcome()).isEqualTo(CONFLICT_REJECTED);
        assertThat(newStore(scorerClient, policy(true), Clock.systemUTC()).lookup(scored, reader.enriched(input)).state()).isNotEqualTo(VERIFIED);
        ownerClient.getDatabase(database).getCollection("source_scored_occurrences").updateOne(new Document(), set("fingerprint", "a".repeat(64)));
        assertThat(store.lookup(scored, reader.enriched(input)).state()).isNotEqualTo(VERIFIED);
        assertThat(store.resume(input, "initial").status()).isEqualTo(UNAVAILABLE); // authenticated ciphertext rejects metadata forgery.
    }

    @Test void encryptedSnapshotHasExplicitExpiryAndCannotBeRecreatedAfterCompaction() throws Exception {
        var input = signedInput(Map.of()); var prepared = store.prepare(input, "initial", candidate(input));
        Document row = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first();
        assertThat(row.toJson()).doesNotContain("Northwind", "203.0.113.24", "cust-1001", "institution-a", "source-event-42", ENCRYPTION_KEY);
        assertThat(row.get("ciphertext", Binary.class).length()).isBetween(17, 262_160);
        var late = newStore(scorerClient, policy, Clock.offset(Clock.systemUTC(), Duration.ofHours(3)));
        assertThat(late.claim(prepared.id()).status()).isEqualTo(UNAVAILABLE);
        assertThat(late.compactExpired(1)).isEqualTo(1);
        row = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first();
        assertThat(row.getString("state")).isEqualTo("EXPIRED"); assertThat(row).doesNotContainKeys("ciphertext", "nonce", "keyId");
        assertThat(store.resume(input, "initial").status()).isEqualTo(UNAVAILABLE);
        assertThat(store.prepare(input, "initial", candidate(input)).status()).isEqualTo(UNAVAILABLE);
    }

    @Test void automaticPublicationAttemptsAreBoundedAndWrongKeyOrMissingReceiptFailsClosed() throws Exception {
        var input = signedInput(Map.of());
        var prepared = store.prepare(input, "initial", candidate(input));
        for (int i = 0; i < 12; i++) {
            var retryStore = newStore(scorerClient, policy, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5L * i)));
            new SourceOccurrencePublisher(retryStore, event -> { throw new IllegalStateException("test unavailable"); }).recoverPending(1);
        }
        Document row = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first();
        assertThat(row.getInteger("attempts")).isEqualTo(8); assertThat(store.pending(1)).isEmpty();
        assertThat(store.claim(prepared.id()).status()).isEqualTo(RETRY_EXHAUSTED);
        assertThat(store.recoveryHealth().exhausted()).isEqualTo(1);
        assertThat(new SourceOccurrencePublisher(store, kafkaPublisher)
                .redriveExhausted(prepared.id(), MongoSourceOccurrenceStore.RedriveReason.BROKER_RECOVERED)).isTrue();
        assertThat(store.resume(input, "initial").status()).isEqualTo(PUBLISHED);
        row = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find().first();
        assertThat(row.getInteger("recoveryEpoch")).isEqualTo(1);
        assertThat(row.getString("redriveReason")).isEqualTo("BROKER_RECOVERED");
        assertThat(row.getString("state")).isEqualTo("PUBLISHED");
        byte[] differentKey = new byte[32]; differentKey[0] = 1;
        var wrongKey = new MongoSourceOccurrenceStore(scorerClient, database, "scorer", policy, Clock.systemUTC(),
                new SourceOccurrenceCipher("key-1", Map.of("key-1", Base64.getEncoder().encodeToString(differentKey))), Duration.ofHours(1));
        assertThat(wrongKey.resume(input, "initial").status()).isEqualTo(UNAVAILABLE);
        ownerClient.getDatabase(database).getCollection("source_admission_receipts").deleteMany(new Document());
        assertThat(store.claim(prepared.id()).status()).isEqualTo(UNAVAILABLE);
        assertThatThrownBy(() -> coordinator.recoverPending(101)).hasMessage("SOURCE_OCCURRENCE_LIMIT_INVALID");
    }

    @Test void crashedEighthPublishingLeaseIsTerminalizedInMongoAndExactFactCanBeRedriven() throws Exception {
        var input = signedInput(Map.of());
        var prepared = store.prepare(input, "initial", candidate(input));
        for (int attempt = 0; attempt < 7; attempt++) {
            var retryStore = newStore(scorerClient, policy, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5L * attempt)));
            new SourceOccurrencePublisher(retryStore, event -> { throw new IllegalStateException("test unavailable"); }).recoverPending(1);
        }
        ownerClient.getDatabase(database).getCollection("source_scored_occurrences")
                .updateOne(eq("_id", prepared.id()), set("nextAttemptAt", Date.from(Instant.EPOCH)));

        var eighthClaim = store.claim(prepared.id());
        assertThat(eighthClaim.status()).isEqualTo(PUBLISHING);
        assertThat(eighthClaim.claimId()).isNotBlank();
        Document crashed = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find(eq("_id", prepared.id())).first();
        assertThat(crashed.getInteger("attempts")).isEqualTo(8);
        assertThat(crashed.getString("state")).isEqualTo("PUBLISHING");

        var restarted = newStore(scorerClient, policy, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)));
        assertThat(restarted.claim(prepared.id()).status()).isEqualTo(RETRY_EXHAUSTED);
        Document terminal = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find(eq("_id", prepared.id())).first();
        assertThat(terminal.getString("state")).isEqualTo("RETRY_EXHAUSTED");
        assertThat(terminal).doesNotContainKeys("leaseId", "leaseUntil", "nextAttemptAt");
        assertThat(restarted.pending(1)).isEmpty();
        assertThat(restarted.recoveryHealth().exhausted()).isEqualTo(1);

        assertThat(new SourceOccurrencePublisher(restarted, kafkaPublisher)
                .redriveExhausted(prepared.id(), MongoSourceOccurrenceStore.RedriveReason.BROKER_RECOVERED)).isTrue();
        TransactionScoredEvent delivered = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        assertThat(delivered).isEqualTo(prepared.event());
        terminal = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find(eq("_id", prepared.id())).first();
        assertThat(terminal.getString("state")).isEqualTo("PUBLISHED");
        assertThat(terminal.getInteger("recoveryEpoch")).isEqualTo(1);
    }

    @Test void concurrentConfirmationAndExpiredEighthLeaseTerminalizationCannotOverwriteEachOther() throws Exception {
        var input = signedInput(Map.of());
        var prepared = store.prepare(input, "initial", candidate(input));
        var originalClaim = store.claim(prepared.id());
        assertThat(originalClaim.status()).isEqualTo(PUBLISHING);
        ownerClient.getDatabase(database).getCollection("source_scored_occurrences").updateOne(eq("_id", prepared.id()),
                com.mongodb.client.model.Updates.combine(set("attempts", 8), set("leaseUntil", Date.from(Instant.EPOCH))));
        var restarted = newStore(scorerClient, policy, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var confirmation = pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("CONFIRMATION_RACE_START_TIMEOUT"); }
                return store.confirm(prepared.id(), prepared.event(), originalClaim.claimId());
            });
            var terminalization = pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("TERMINALIZATION_RACE_START_TIMEOUT"); }
                return restarted.claim(prepared.id());
            });
            start.countDown();
            var confirmed = confirmation.get(20, TimeUnit.SECONDS);
            var exhausted = terminalization.get(20, TimeUnit.SECONDS);
            Document row = ownerClient.getDatabase(database).getCollection("source_scored_occurrences").find(eq("_id", prepared.id())).first();
            assertThat(row.getString("state")).isIn("PUBLISHED", "RETRY_EXHAUSTED");
            if ("PUBLISHED".equals(row.getString("state"))) {
                assertThat(exhausted.status()).isNotEqualTo(RETRY_EXHAUSTED);
                assertThat(confirmed.status()).isEqualTo(PUBLISHED);
            } else {
                assertThat(confirmed.status()).isNotEqualTo(PUBLISHED);
                assertThat(exhausted.status()).isEqualTo(RETRY_EXHAUSTED);
            }
        }
    }

    @Test void quarantinedRawPublicationRequiresRedriveAndDeliversTheOriginalStoredBytesAfterKafkaRecovery() throws Exception {
        var verification = policy.verify(TestSourceIssuer.proof(PAYLOAD, Map.of(), 0), PAYLOAD, sourceContext());
        assertThat(verification).isInstanceOf(SourceSignatureVerifier.Verified.class);
        var request = JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class);
        TransactionRawEvent original = new TransactionRequestMapper().toEvent(request, "raw-redrive-correlation");
        var admitted = receipts.acceptRaw((SourceSignatureVerifier.Verified) verification, original);
        assertThat(admitted.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);
        String receiptId = admitted.receipt().id();

        for (int attempt = 0; attempt < 8; attempt++) {
            var retryStore = newReceiptStore(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5L * attempt)));
            assertThat(retryStore.recoverRawPublications(event -> { throw new IllegalStateException("simulated Kafka outage"); }, 1))
                    .isEqualTo(1);
        }
        var outbox = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", receiptId)).first();
        assertThat(outbox.getString("state")).isEqualTo("QUARANTINED");
        assertThat(outbox.getString("failureCode")).isEqualTo("PUBLISH_FAILED");
        byte[] storedBytes = new SourceRawPublicationCipher("raw-key-1", RAW_PUBLICATION_PROPERTIES.encryptionKeys()).decrypt(outbox);
        var health = receipts.recoveryHealth();
        assertThat(health.quarantinedPublishFailed()).isEqualTo(1);
        assertThat(health.redriveEpochExhausted()).isZero();

        var redrive = receipts.redriveQuarantined(receiptId, SourceReceiptStore.RedriveReason.BROKER_RECOVERED);
        assertThat(redrive.status()).isEqualTo(SourceReceiptStore.RedriveStatus.REQUEUED);
        assertThat(redrive.recoveryEpoch()).isEqualTo(1);
        assertThat(receipts.recoverRawPublications(new KafkaTransactionRawEventProducer(template(),
                new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic), ORIGIN_PROOFS), 1)).isEqualTo(1);

        TransactionRawEvent delivered = records(rawTopic, TransactionRawEvent.class).getFirst();
        assertThat(delivered).isEqualTo(original);
        assertThat(JSON.writeValueAsBytes(delivered)).containsExactly(storedBytes);
        outbox = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", receiptId)).first();
        assertThat(outbox.getString("state")).isEqualTo("PUBLISHED");
        assertThat(outbox.getInteger("recoveryEpoch")).isEqualTo(1);
        assertThat(outbox.getString("redriveReason")).isEqualTo("BROKER_RECOVERED");
        assertThat(ownerClient.getDatabase(database).getCollection("source_admission_receipts").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(database).getCollection("source_lineage_raw").countDocuments()).isEqualTo(1);
        assertThat(receipts.recoveryHealth().quarantinedPublishFailed()).isZero();
    }

    @Test void rawRedriveIsBoundedAndConcurrentRequestsDoNotCreateMultipleEpochs() throws Exception {
        var quarantined = quarantineRawPublication();
        assertThatThrownBy(() -> new SourceRawPublicationRedriveProperties(true, quarantined.receiptId(), "FORCE"))
                .hasMessage("SOURCE_RAW_REDRIVE_CONFIGURATION_INVALID");
        assertThat(receipts.redriveQuarantined(quarantined.receiptId(), SourceReceiptStore.RedriveReason.BROKER_RECOVERED)
                .status()).isEqualTo(SourceReceiptStore.RedriveStatus.REQUEUED);

        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return receipts.redriveQuarantined(quarantined.receiptId(),
                    SourceReceiptStore.RedriveReason.OPERATOR_RECONCILIATION); });
            var second = pool.submit(() -> { gate.await(); return receipts.redriveQuarantined(quarantined.receiptId(),
                    SourceReceiptStore.RedriveReason.OPERATOR_RECONCILIATION); });
            gate.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }
        Document row = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", quarantined.receiptId())).first();
        assertThat(row.getInteger("recoveryEpoch")).isEqualTo(1);
        assertThat(row.getString("state")).isEqualTo("PENDING");
        byte[] preserved = new SourceRawPublicationCipher("raw-key-1", RAW_PUBLICATION_PROPERTIES.encryptionKeys()).decrypt(row);
        assertThat(JSON.readValue(preserved, TransactionRawEvent.class)).isEqualTo(quarantined.event());
    }

    @Test void rawRedriveRefusesTamperedCiphertextAndDoesNotClearQuarantine() throws Exception {
        var quarantined = quarantineRawPublication();
        Document row = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", quarantined.receiptId())).first();
        byte[] ciphertext = row.get("ciphertext", org.bson.types.Binary.class).getData().clone();
        ciphertext[ciphertext.length - 1] ^= 1;
        ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .updateOne(eq("_id", quarantined.receiptId()), com.mongodb.client.model.Updates.set("ciphertext",
                        new org.bson.types.Binary(ciphertext)));

        assertThat(receipts.redriveQuarantined(quarantined.receiptId(), SourceReceiptStore.RedriveReason.BROKER_RECOVERED).status())
                .isEqualTo(SourceReceiptStore.RedriveStatus.AUTHORITY_DENIED);
        row = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", quarantined.receiptId())).first();
        assertThat(row.getString("state")).isEqualTo("QUARANTINED");
        assertThat(row.getString("failureCode")).isEqualTo("PAYLOAD_UNAVAILABLE");
        assertThat(row.getInteger("recoveryEpoch")).isEqualTo(0);
    }

    @Test void rawRedriveRefusesExpiredReceiptAndCurrentPolicyRevocation() throws Exception {
        var expired = quarantineRawPublication();
        Date expiredAt = Date.from(Instant.now().minusSeconds(1));
        ownerClient.getDatabase(database).getCollection("source_admission_receipts")
                .updateOne(eq("_id", expired.receiptId()), com.mongodb.client.model.Updates.set("expiresAt", expiredAt));
        ownerClient.getDatabase(database).getCollection("source_admission_identities")
                .updateOne(eq("_id", expired.receiptId()), com.mongodb.client.model.Updates.set("expiresAt", expiredAt));
        ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .updateOne(eq("_id", expired.receiptId()), com.mongodb.client.model.Updates.set("expiresAt", expiredAt));
        ownerClient.getDatabase(database).getCollection("source_lineage_raw")
                .updateOne(eq("receiptId", expired.receiptId()), com.mongodb.client.model.Updates.set("expiresAt", expiredAt));
        assertThat(receipts.redriveQuarantined(expired.receiptId(), SourceReceiptStore.RedriveReason.BROKER_RECOVERED).status())
                .isEqualTo(SourceReceiptStore.RedriveStatus.INVALID);

        var revoked = quarantineRawPublication();
        SourceSignatureVerifier revokedPolicy = policy(true);
        var revokedOwner = new MongoSourceLineage(ownerClient, database, "source_owner", MongoSourceLineage.Stage.INGEST,
                revokedPolicy, Clock.systemUTC());
        var revokedStore = new MongoSourceReceiptStore(ownerClient, database, Clock.systemUTC(),
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), revokedPolicy, revokedOwner,
                new SourceRawPublicationCipher("raw-key-1", RAW_PUBLICATION_PROPERTIES.encryptionKeys()), RAW_PUBLICATION_PROPERTIES);
        assertThat(revokedStore.redriveQuarantined(revoked.receiptId(), SourceReceiptStore.RedriveReason.BROKER_RECOVERED).status())
                .isEqualTo(SourceReceiptStore.RedriveStatus.AUTHORITY_DENIED);
        Document row = ownerClient.getDatabase(database).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", revoked.receiptId())).first();
        assertThat(row.getString("state")).isEqualTo("QUARANTINED");
        assertThat(row.getString("failureCode")).isEqualTo("AUTHORITY_DENIED");
        assertThat(row.getInteger("recoveryEpoch")).isZero();
    }

    @Test void unsignedLegacyScoringKeepsBaselineOutputAndHasNoSourceOccurrenceBinding() throws Exception {
        var raw = new TransactionRequestMapper().toEvent(JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class), "legacy");
        enrichLegacy(raw); var input = records(enrichedTopic, TransactionEnrichedEvent.class).getFirst();
        var baseline = new RuleBasedFraudScoringEngine(properties()).score(FraudScoringRequest.from(input));
        service(coordinator).score(input, ORIGIN_PROOFS.signEnriched(input, SourceOriginProofs.Assertion.PROVEN_LEGACY));
        var event = records(scoredTopic, TransactionScoredEvent.class).getFirst();
        assertThat(event.fraudScore()).isEqualTo(baseline.fraudScore()); assertThat(event.reasonCodes()).isEqualTo(baseline.reasonCodes());
        assertThat(store.lookup(event, reader.enriched(input)).state()).isNotEqualTo(VERIFIED);
        assertThat(store.readOnly(sourceContext()).find(reader.enriched(input), reference(event))).isEmpty();
        assertThat(ownerClient.getDatabase(database).getCollection("source_scored_occurrences").countDocuments()).isZero();
    }

    @Test void explicitlyEnabledSpringFactoryRequiresAndUsesIndependentPoliciesCredentialsKeyAndRetention() throws Exception {
        var input = signedInput(Map.of());
        String root = "fraud.source-verification.keys[0].";
        var policy = TestSourceIssuer.policy(0, false);
        new ApplicationContextRunner().withUserConfiguration(com.frauddetection.scoring.config.SourceLineageConfiguration.class,
                        SourceOccurrenceConfiguration.class).withBean(TransactionScoredEventPublisher.class, () -> kafkaPublisher)
                .withPropertyValues("fraud.source-lineage.enabled=true", "fraud.source-lineage.database=" + database,
                        "fraud.source-lineage.expected-mongo-user=scorer", "fraud.source-lineage.mongo-uri=" + MONGO.scorerUri(database),
                        "fraud.source-occurrences.enabled=true", "fraud.source-occurrences.active-key-id=key-1",
                        "fraud.source-occurrences.encryption-keys[key-1]=" + ENCRYPTION_KEY, "fraud.source-occurrences.payload-retention=PT1H",
                        root + "issuer=" + policy.issuer(), root + "institution=" + policy.institution(), root + "source=" + policy.source(),
                        root + "source-epoch=" + policy.sourceEpoch(), root + "signing-key-version=" + policy.signingKeyVersion(),
                        root + "algorithm=" + policy.algorithm(), root + "public-key-base64=" + policy.publicKeyBase64(),
                        root + "not-before=2000-01-01T00:00:00Z", root + "not-after=2100-01-01T00:00:00Z", root + "revoked=false",
                        root + "purpose=" + policy.purpose(), root + "audience=" + policy.audience(), root + "policy-version=" + policy.policyVersion(),
                        "fraud.source-origin.enabled=true", "fraud.source-origin.active-key-id=origin-key-1",
                        "fraud.source-origin.keys[origin-key-1]=" + originProofKey())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var published = context.getBean(SourceOccurrencePublisher.class).publish(input, "initial", () -> candidate(input));
                    assertThat(context.getBean(MongoSourceOccurrenceStore.class).lookup(published, reader.enriched(input)).state()).isEqualTo(VERIFIED);
                });
        assertThat(records(scoredTopic, TransactionScoredEvent.class)).hasSize(1);
    }

    private TransactionEnrichedEvent signedInput(Map<String, Object> claims) throws Exception {
        return signedInput(claims, null);
    }
    private TransactionEnrichedEvent signedInput(Map<String, Object> claims, FeatureStore features) throws Exception {
        var producer = new KafkaTransactionRawEventProducer(template(), new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic), ORIGIN_PROOFS);
        var ingress = new AttestedTransactionIngestService(new TransactionRequestMapper(), policy, receipts, producer,
                new CorrelationIdProvider(), new com.frauddetection.ingest.config.KafkaTopicProperties(rawTopic), validation.getValidator());
        var mvc = MockMvcBuilders.standaloneSetup(new AttestedTransactionIngestController(ingress))
                .setControllerAdvice(new TransactionIngestExceptionHandler()).build();
        var response = mvc.perform(post("/api/v1/transactions/attested").contentType("application/json").content(PAYLOAD)
                .header("X-Source-Proof", Base64.getUrlEncoder().withoutPadding().encodeToString(TestSourceIssuer.proof(PAYLOAD, claims, 0))))
                .andReturn().getResponse();
        lastIngestResponse = response.getContentAsString();
        assertThat(response.getStatus()).withFailMessage("Attested ingress failed: %s", lastIngestResponse).isEqualTo(202);
        var raw = records(rawTopic, TransactionRawEvent.class).getLast();
        if (features == null) { enrich(raw); } else { enrich(raw, features); }
        return records(enrichedTopic, TransactionEnrichedEvent.class).getLast();
    }
    private void enrich(TransactionRawEvent raw) {
        var features = new FeatureStore() {
            public boolean hasRecordedTransaction(TransactionRawEvent event) { return false; }
            public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) { return false; }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) { return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false); }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
            }
            public void recordTransaction(TransactionRawEvent event) { }
            public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) { }
        };
        enrich(raw, features);
    }
    private void enrich(TransactionRawEvent raw, FeatureStore features) {
        var publisher = new KafkaTransactionEnrichedEventPublisher(template(),
                new com.frauddetection.enricher.config.KafkaTopicProperties(rawTopic, enrichedTopic, database + ".dlt"), ORIGIN_PROOFS);
        new TransactionFeatureEnricherService(features, new TransactionFeatureCalculator(new CurrencyAmountConverter()),
                new TransactionEnrichedEventMapper(), publisher, enricher, ORIGIN_PROOFS).enrich(raw);
    }
    private void enrichLegacy(TransactionRawEvent raw) {
        FeatureStore features = new FeatureStore() {
            public boolean hasRecordedTransaction(TransactionRawEvent event) { return false; }
            public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) { return false; }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) { return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false); }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
            }
            public void recordTransaction(TransactionRawEvent event) { }
            public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) { }
        };
        var publisher = new KafkaTransactionEnrichedEventPublisher(template(),
                new com.frauddetection.enricher.config.KafkaTopicProperties(rawTopic, enrichedTopic, database + ".dlt"), ORIGIN_PROOFS);
        new TransactionFeatureEnricherService(features, new TransactionFeatureCalculator(new CurrencyAmountConverter()),
                new TransactionEnrichedEventMapper(), publisher, enricher, ORIGIN_PROOFS)
                .enrich(raw, ORIGIN_PROOFS.signRaw(raw, SourceOriginProofs.Assertion.PROVEN_LEGACY));
    }
    private TransactionFraudScoringService service(SourceOccurrencePublisher publisher) {
        var rules = new RuleBasedFraudScoringEngine(properties());
        return new TransactionFraudScoringService(request -> { computations.incrementAndGet(); return rules.score(request); },
                new TransactionScoredEventMapper(), kafkaPublisher, properties(), new ScoringMetrics(metrics), emission(),
                new AnalystRecommendationService(), reader, publisher, ORIGIN_PROOFS);
    }
    private static TransactionScoredEvent candidate(TransactionEnrichedEvent input) {
        var request = FraudScoringRequest.from(input);
        return new TransactionScoredEventMapper().toEvent(request, new RuleBasedFraudScoringEngine(properties()).score(request),
                Optional.empty(), com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED,
                new AnalystRecommendationService().recommend(new RuleBasedFraudScoringEngine(properties()).score(request), Optional.empty()));
    }
    private static EngineIntelligenceEmissionService emission() {
        return new EngineIntelligenceEmissionService(new EngineIntelligenceEmissionProperties(false),
                new DefaultListableBeanFactory().getBeanProvider(EngineIntelligenceDiagnosticEnrichmentPipeline.class), mock(EngineIntelligenceEmissionMetrics.class));
    }
    private static ScoringProperties properties() { return new ScoringProperties(.75d, .90d, ScoringMode.RULE_BASED); }
    private static SourceSignatureVerifier.Context sourceContext() {
        return new SourceSignatureVerifier.Context(AttestedTransactionIngestService.PURPOSE, AttestedTransactionIngestService.AUDIENCE);
    }
    private static SourceOccurrenceAccess.Reference reference(TransactionScoredEvent event) {
        return new SourceOccurrenceAccess.Reference(event.eventId(), event.createdAt(), ScoringOccurrenceFingerprint.from(event));
    }
    private void configurePolicies(SourceVerificationProperties.TrustedKey... keys) {
        policy = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(keys)), Clock.systemUTC());
        owner = new MongoSourceLineage(ownerClient, database, "source_owner", MongoSourceLineage.Stage.INGEST, policy, Clock.systemUTC());
        receipts = new MongoSourceReceiptStore(ownerClient, database, Clock.systemUTC(),
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), policy, owner,
                new SourceRawPublicationCipher("raw-key-1", RAW_PUBLICATION_PROPERTIES.encryptionKeys()), RAW_PUBLICATION_PROPERTIES);
        enricher = new MongoSourceLineage(enricherClient, database, "enricher", MongoSourceLineage.Stage.ENRICHER, policy, Clock.systemUTC(),
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_ENCRYPTION_KEY)));
        reader = new MongoSourceLineage(scorerClient, database, "scorer", MongoSourceLineage.Stage.SCORER, policy, Clock.systemUTC());
        store = newStore(scorerClient, policy, Clock.systemUTC()); coordinator = new SourceOccurrencePublisher(store, kafkaPublisher);
    }
    private static MerchantRiskEvidenceClaim merchantClaim(TransactionEnrichedEvent input) {
        var time = input.transactionTimestamp();
        return new MerchantRiskEvidenceClaim(1,
                new MerchantRiskEvidenceClaim.ScopeClaim("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64), 1),
                new MerchantRiskEvidenceClaim.OccurrenceClaim("f".repeat(64), "1".repeat(64), "2".repeat(64), 1, time, "3".repeat(64)),
                new MerchantRiskEvidenceClaim.SourceClaim(MerchantRiskEvidenceClaim.SourceAssertion.UNSPECIFIED, time),
                new MerchantRiskEvidenceClaim.HistoryWindowClaim(time.minusSeconds(60), time, null, null, MerchantRiskEvidenceClaim.CoverageClaim.INCOMPLETE),
                Arrays.stream(MerchantRiskEvidenceClaim.Signal.values()).map(signal -> new MerchantRiskEvidenceClaim.SignalFact(signal,
                        MerchantRiskEvidenceClaim.Status.UNAVAILABLE, MerchantRiskEvidenceClaim.Reason.NOT_ADMITTED)).toList());
    }
    private MongoSourceOccurrenceStore newStore(MongoClient client, SourceSignatureVerifier verifier, Clock clock) {
        return new MongoSourceOccurrenceStore(client, database, "scorer", verifier, clock,
                new SourceOccurrenceCipher("key-1", Map.of("key-1", ENCRYPTION_KEY)), Duration.ofHours(1));
    }
    private MongoSourceReceiptStore newReceiptStore(Clock clock) {
        return new MongoSourceReceiptStore(ownerClient, database, clock,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), policy, owner,
                new SourceRawPublicationCipher("raw-key-1", RAW_PUBLICATION_PROPERTIES.encryptionKeys()), RAW_PUBLICATION_PROPERTIES);
    }
    private QuarantinedRaw quarantineRawPublication() throws Exception {
        var verification = policy.verify(TestSourceIssuer.proof(PAYLOAD,
                Map.of("sourceEventId", "raw-redrive-" + UUID.randomUUID()), 0), PAYLOAD, sourceContext());
        assertThat(verification).isInstanceOf(SourceSignatureVerifier.Verified.class);
        var request = JSON.readValue(PAYLOAD, com.frauddetection.ingest.api.IngestTransactionRequest.class);
        TransactionRawEvent event = new TransactionRequestMapper().toEvent(request, "raw-redrive-" + UUID.randomUUID());
        var admitted = receipts.acceptRaw((SourceSignatureVerifier.Verified) verification, event);
        assertThat(admitted.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);
        for (int attempt = 0; attempt < 8; attempt++) {
            var retryStore = newReceiptStore(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5L * attempt)));
            assertThat(retryStore.recoverRawPublications(ignored -> { throw new IllegalStateException("simulated Kafka outage"); }, 1))
                    .isEqualTo(1);
        }
        return new QuarantinedRaw(admitted.receipt().id(), event);
    }
    private record QuarantinedRaw(String receiptId, TransactionRawEvent event) { }
    private static SourceSignatureVerifier policy(boolean revoked) {
        return new SourceSignatureVerifier(new SourceVerificationProperties(List.of(TestSourceIssuer.policy(0, revoked))), Clock.systemUTC());
    }
    private static String rawEncryptionKey() {
        byte[] bytes = new byte[32]; bytes[0] = 2;
        return Base64.getEncoder().encodeToString(bytes);
    }
    private static String enrichedEncryptionKey() {
        byte[] bytes = new byte[32]; bytes[0] = 1;
        return Base64.getEncoder().encodeToString(bytes);
    }
    private static String originProofKey() {
        byte[] bytes = new byte[32]; bytes[0] = 3;
        return Base64.getEncoder().encodeToString(bytes);
    }
    private <T> KafkaTemplate<String, T> template() {
        var factory = new DefaultKafkaProducerFactory<String, T>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "delivery.timeout.ms", 10_000, "request.timeout.ms", 5_000, "max.block.ms", 10_000), new StringSerializer(), new JacksonKafkaSerializer<>());
        factories.add(factory); return new KafkaTemplate<>(factory);
    }
    private <T> List<T> records(String topic, Class<T> type) {
        try (var consumer = new KafkaConsumer<String, T>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", UUID.randomUUID().toString(), "enable.auto.commit", false), new StringDeserializer(), new JacksonKafkaDeserializer<>(type))) {
            var partition = new TopicPartition(topic, 0); consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            var values = new ArrayList<T>(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (consumer.position(partition) < end && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) { values.add(record.value()); }
            }
            assertThat(consumer.position(partition)).isGreaterThanOrEqualTo(end);
            return values;
        }
    }
    private static <T> T change(Object value, String field, Object replacement, Class<T> type) {
        ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(value)); node.set(field, JSON.valueToTree(replacement)); return JSON.treeToValue(node, type);
    }
    private static byte[] payload() {
        ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(TransactionFixtures.rawTransaction().build()));
        node.remove("eventId"); node.remove("createdAt"); node.remove("correlationId"); return JSON.writeValueAsBytes(node);
    }
    /** Lose the acknowledgement for the store transaction commit, either before or after its durable effect. */
    private static MongoClient commitFault(MongoClient delegate, boolean durable) {
        AtomicInteger commits = new AtomicInteger();
        return (MongoClient) Proxy.newProxyInstance(MongoClient.class.getClassLoader(), new Class<?>[]{MongoClient.class}, (proxy, method, args) -> {
            Object value = invoke(delegate, method, args);
            if (!method.getName().equals("startSession")) { return value; }
            ClientSession session = (ClientSession) value;
            return Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[]{ClientSession.class}, (ignored, operation, parameters) -> {
                if (operation.getName().equals("commitTransaction")) {
                    if (durable && commits.getAndIncrement() == 0) { invoke(session, operation, parameters); }
                    var failure = new MongoException(91, "TEST_ONLY_LOST_COMMIT_ACK");
                    failure.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL); throw failure;
                }
                return invoke(session, operation, parameters);
            });
        });
    }
    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(delegate, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    /** A separate OS process commits the actual snapshot and dies before any Kafka publication or clean client shutdown. */
    public static final class CrashAfterPrepare {
        public static void main(String[] ignored) throws Exception {
            var request = JSON.readTree(System.in.readAllBytes());
            var input = JSON.treeToValue(request.get("input"), TransactionEnrichedEvent.class);
            var candidate = JSON.treeToValue(request.get("candidate"), TransactionScoredEvent.class);
            MongoClient client = MongoClients.create(MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(request.get("uri").asText())).timeout(5, TimeUnit.SECONDS).build());
            var store = new MongoSourceOccurrenceStore(client, request.get("database").asText(), "scorer", policy(false), Clock.systemUTC(),
                    new SourceOccurrenceCipher("key-1", Map.of("key-1", ENCRYPTION_KEY)), Duration.ofHours(1));
            if (store.prepare(input, "initial", candidate).status() != PENDING) { System.exit(18); }
            Runtime.getRuntime().halt(17);
        }
    }
}
