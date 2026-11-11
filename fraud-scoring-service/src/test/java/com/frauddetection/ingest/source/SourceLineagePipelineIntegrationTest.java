package com.frauddetection.ingest.source;

import com.frauddetection.common.events.contract.*;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.source.*;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.config.FeatureStoreProperties;
import com.frauddetection.enricher.config.SourceEnrichedPublicationRecoveryProperties;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.messaging.TransactionRawEventListener;
import com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher;
import com.frauddetection.enricher.persistence.FeatureStore;
import com.frauddetection.enricher.persistence.FeatureNamespace;
import com.frauddetection.enricher.persistence.RedisFeatureStore;
import com.frauddetection.enricher.service.*;
import com.frauddetection.ingest.api.IngestTransactionRequest;
import com.frauddetection.ingest.config.KafkaTopicProperties;
import com.frauddetection.ingest.controller.AttestedTransactionIngestController;
import com.frauddetection.ingest.exception.TransactionIngestExceptionHandler;
import com.frauddetection.ingest.mapper.TransactionRequestMapper;
import com.frauddetection.ingest.messaging.TransactionRawEventProducer;
import com.frauddetection.ingest.observability.CorrelationIdProvider;
import com.frauddetection.ingest.service.TransactionIngestService;
import com.frauddetection.scoring.config.*;
import com.frauddetection.scoring.context.*;
import com.frauddetection.scoring.domain.FraudScoringRequest;
import com.frauddetection.scoring.mapper.TransactionScoredEventMapper;
import com.frauddetection.scoring.observability.ScoringMetrics;
import com.frauddetection.scoring.orchestration.aggregation.*;
import com.frauddetection.scoring.service.*;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.bson.Document;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.frauddetection.common.events.source.SourceLineage.State.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Composition proof with real crypto, authenticated transactional Mongo, services/mappers and Kafka serde.
 * It does not claim Redis atomicity or exact post-scoring occurrence recovery (the next gate).
 */
class SourceLineagePipelineIntegrationTest {
    private static final AuthenticatedSourceMongo MONGO = new AuthenticatedSourceMongo();
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Clock CLOCK = Clock.fixed(TestSourceIssuer.NOW, ZoneOffset.UTC);
    private static final String RAW_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ENRICHED_KEY = Base64.getEncoder().encodeToString(new byte[] {1, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    private static final SourceOriginProofs ORIGIN_PROOFS = new SourceOriginProofs(new SourceOriginProperties(true, "origin-key-1",
            Map.of("origin-key-1", Base64.getEncoder().encodeToString(new byte[32]))));
    private static final SourceRawPublicationProperties RAW_PUBLICATION_PROPERTIES = new SourceRawPublicationProperties(
            true, "raw-key-1", Map.of("raw-key-1", RAW_KEY), Duration.ofSeconds(1), Duration.ofSeconds(30), 10, 8);
    private static ValidatorFactory validation;
    private MongoClient ownerClient;
    private MongoClient enricherClient;
    private MongoClient scorerClient;
    private String db;
    private SourceSignatureVerifier verifier;
    private MongoSourceReceiptStore receipts;
    private MongoSourceLineage owner;
    private MongoSourceLineage enricher;
    private MongoSourceLineage scorer;
    private final List<byte[]> rawWire = new ArrayList<>();
    private final List<byte[]> enrichedWire = new ArrayList<>();
    private final Set<String> attestedRawEventIds = new HashSet<>();
    private final Map<String, String> enrichedOriginProofs = new HashMap<>();
    private final Map<FeatureNamespace, Set<String>> recorded = new HashMap<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeAll static void start() throws Exception { MONGO.start(); validation = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void stop() { if (validation != null) { validation.close(); } MONGO.close(); }

    @BeforeEach void setUp() {
        db = "lineage_" + UUID.randomUUID().toString().replace("-", "");
        rawWire.clear(); enrichedWire.clear(); attestedRawEventIds.clear(); enrichedOriginProofs.clear(); recorded.clear();
        ownerClient = MONGO.owner();
        verifier = policy(false);
        new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier);
        owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, owner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        SourceEnrichedPublicationCipher publicationCipher =
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY));
        SourceEnrichedBusinessIdentityMigration.migrate(ownerClient.getDatabase(db), publicationCipher, CLOCK);
        enricherClient = MONGO.consumer(ownerClient, db, true);
        scorerClient = MONGO.consumer(ownerClient, db, false);
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK,
                publicationCipher);
        scorer = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, verifier, CLOCK);
    }

    @AfterEach void cleanUp() { metrics.close(); ownerClient.getDatabase(db).drop(); }

    @Test
    @EnabledIfSystemProperty(named = "source.business-identity.startup.benchmark", matches = "true")
    void recordsOptInBusinessIdentityStartupScanProfile() {
        var database = ownerClient.getDatabase(db);
        var identities = database.getCollection("source_enriched_business_identities");
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var allocationBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        System.out.printf("BUSINESS_IDENTITY_STARTUP_ENV jdk=%s maxHeapBytes=%d hostTotalMemoryBytes=%d "
                        + "hostFreeMemoryBytes=%d mongoImage=mongo:7.0%n",
                System.getProperty("java.version"), Runtime.getRuntime().maxMemory(),
                os.getTotalMemorySize(), os.getFreeMemorySize());

        for (int fenceCount : List.of(0, 10_000, 100_000)) {
            identities.deleteMany(new Document("_id", new Document("$ne", "0".repeat(64))));
            seedBusinessIdentityFences(identities, fenceCount);
            identities.updateOne(eq("_id", "0".repeat(64)),
                    set("backfilledRows", fenceCount));
            Document explain = database.runCommand(new Document("explain", new Document("find", "source_enriched_business_identities")
                    .append("filter", new Document()).append("limit", 100_002)).append("verbosity", "executionStats"));
            Document executionStats = explain.get("executionStats", Document.class);
            int explainDocsExamined = executionStats.getInteger("totalDocsExamined");
            assertThat(explainDocsExamined).isEqualTo(fenceCount + 1);

            for (int initializations : List.of(1, 3)) {
                long heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                long threadId = Thread.currentThread().threadId();
                long allocatedBefore = allocatedBytes(allocationBean, threadId);
                long start = System.nanoTime();
                for (int index = 0; index < initializations; index++) { newEnricher(CLOCK, verifier); }
                long elapsedNanos = System.nanoTime() - start;
                long allocatedBytes = allocatedBytes(allocationBean, threadId) - allocatedBefore;
                long heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                System.out.printf("BUSINESS_IDENTITY_STARTUP_RESULT fences=%d documentsExpectedChecked=%d "
                                + "explainDocsExamined=%d explainMs=%d initializations=%d totalMs=%.3f "
                                + "msPerInitialization=%.3f allocatedBytesPerInitialization=%d "
                                + "heapUsedBeforeBytes=%d heapUsedAfterBytes=%d heapDeltaBytes=%d%n",
                        fenceCount, fenceCount + 1, explainDocsExamined,
                        executionStats.getInteger("executionTimeMillis"), initializations,
                        elapsedNanos / 1_000_000.0d, elapsedNanos / 1_000_000.0d / initializations,
                        allocationBean.isThreadAllocatedMemorySupported() && allocationBean.isThreadAllocatedMemoryEnabled()
                                ? allocatedBytes / initializations : -1,
                        heapBefore, heapAfter, heapAfter - heapBefore);
            }
        }
    }

    private static void seedBusinessIdentityFences(com.mongodb.client.MongoCollection<Document> identities, int count) {
        Date expiry = Date.from(CLOCK.instant().plus(Duration.ofDays(1)));
        for (int start = 0; start < count; start += 1_000) {
            List<Document> batch = new ArrayList<>(Math.min(1_000, count - start));
            for (int index = start; index < Math.min(count, start + 1_000); index++) {
                String id = String.format(Locale.ROOT, "%064x", index + 1L);
                String receiptId = String.format(Locale.ROOT, "%064x", index + 100_001L);
                String rawCommitment = String.format(Locale.ROOT, "%064x", index + 200_001L);
                String enrichedCommitment = String.format(Locale.ROOT, "%064x", index + 300_001L);
                batch.add(new Document("_id", id).append("schemaVersion", 1).append("recordType", "FENCE")
                        .append("receiptId", receiptId).append("rawCommitment", rawCommitment)
                        .append("enrichedCommitment", enrichedCommitment).append("expiresAt", expiry));
            }
            if (!batch.isEmpty()) { identities.insertMany(batch); }
        }
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean bean, long threadId) {
        return bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()
                ? bean.getThreadAllocatedBytes(threadId) : 0;
    }

    @Test void rawRecoverySchemaMigrationIsOwnerOnlyAdditiveAndPreservesExistingV1Rows() {
        String migrationDatabase = "raw_migration_" + UUID.randomUUID().toString().replace("-", "");
        var database = ownerClient.getDatabase(migrationDatabase);
        database.createCollection(MongoSourceRawPublicationSchema.COLLECTION,
                new com.mongodb.client.model.CreateCollectionOptions().validationOptions(new com.mongodb.client.model.ValidationOptions()
                        .validator(MongoSourceRawPublicationSchema.validatorV1())
                        .validationLevel(com.mongodb.client.model.ValidationLevel.STRICT)
                        .validationAction(com.mongodb.client.model.ValidationAction.ERROR)));
        database.getCollection(MongoSourceRawPublicationSchema.COLLECTION).createIndex(MongoSourceRawPublicationSchema.RECOVERY_KEY,
                new com.mongodb.client.model.IndexOptions().name(MongoSourceRawPublicationSchema.RECOVERY_INDEX));
        String receipt = "a".repeat(64);
        Document legacy = new Document("_id", receipt).append("schemaVersion", 1).append("receiptId", receipt)
                .append("rawCommitment", "b".repeat(64)).append("eventId", "legacy-raw-event")
                .append("eventCreatedAt", TestSourceIssuer.NOW.toString()).append("createdAt", Date.from(TestSourceIssuer.NOW))
                .append("expiresAt", Date.from(TestSourceIssuer.NOW.plus(Duration.ofHours(1))))
                .append("state", "PENDING").append("attempts", 0).append("nextAttemptAt", Date.from(TestSourceIssuer.NOW))
                .append("keyId", "raw-key-1").append("nonce", new org.bson.types.Binary(new byte[12]))
                .append("ciphertext", new org.bson.types.Binary(new byte[17]));
        database.getCollection(MongoSourceRawPublicationSchema.COLLECTION).insertOne(legacy);

        assertThatThrownBy(() -> MongoSourceRawPublicationSchema.migrateRecoveryValidator(database, false))
                .hasMessage("SOURCE_RAW_PUBLICATION_MIGRATION_OWNER_REQUIRED");
        SourceRawPublicationRecoveryMigration.migrateValidator(database);

        MongoSourceRawPublicationSchema.initialize(database);
        Document preserved = database.getCollection(MongoSourceRawPublicationSchema.COLLECTION).find(eq("_id", receipt)).first();
        assertThat(preserved).containsEntry("state", "PENDING").containsEntry("attempts", 0).doesNotContainKey("recoveryEpoch");
        database.drop();
    }

    @Test void featureOrderGateMigrationFailsClosedUntilLegacyHistoryIsResolved() throws Exception {
        var raw = admit(Map.of("sourceEventId", "feature-order-migration"));
        enrich(raw);
        var database = ownerClient.getDatabase(db);
        var gates = database.getCollection("source_feature_order_gates");
        var publications = database.getCollection(SourceEnrichedPublicationCipher.COLLECTION);
        Document publication = publications.find(new Document()).first();
        assertThat(publication).isNotNull();
        gates.drop();

        publications.updateOne(eq("_id", publication.getString("_id")), set("historyState", "PENDING"));
        assertThatThrownBy(() -> SourceFeatureOrderGateMigration.migrate(database, CLOCK))
                .hasMessage("SOURCE_FEATURE_ORDER_GATE_ENRICHED_HISTORY_PENDING");
        assertThat(database.listCollectionNames().into(new ArrayList<>())).doesNotContain("source_feature_order_gates");

        publications.replaceOne(eq("_id", publication.getString("_id")), publication);
        publications.deleteOne(eq("_id", publication.getString("_id")));
        assertThatThrownBy(() -> SourceFeatureOrderGateMigration.migrate(database, CLOCK))
                .hasMessage("SOURCE_FEATURE_ORDER_GATE_RAW_HISTORY_UNRESOLVED");
        assertThat(database.listCollectionNames().into(new ArrayList<>())).doesNotContain("source_feature_order_gates");

        publications.insertOne(publication);
        SourceFeatureOrderGateMigration.migrate(database, CLOCK);
        assertThat(database.listCollectionNames().into(new ArrayList<>())).contains("source_feature_order_gates");
        Document collection = database.listCollections().filter(eq("name", "source_feature_order_gates")).first();
        Document options = collection.get("options", Document.class);
        assertThat(options.getString("validationLevel")).isEqualTo("strict");
        assertThat(options.getString("validationAction")).isEqualTo("error");
        List<Document> indexes = gates.listIndexes().into(new ArrayList<>());
        assertThat(indexes).hasSize(1);
        assertThat(indexes.getFirst().getString("name")).isEqualTo("_id_");
        assertThat(indexes.getFirst()).doesNotContainKey("expireAfterSeconds");
    }

    @Test void enrichedRecoveryMigrationIsOwnerOnlyAdditiveAndPreservesLegacyPublicationRows() {
        String migrationDatabase = "enriched_migration_" + UUID.randomUUID().toString().replace("-", "");
        var database = ownerClient.getDatabase(migrationDatabase);
        database.createCollection("source_enriched_publications", new com.mongodb.client.model.CreateCollectionOptions()
                .validationOptions(new com.mongodb.client.model.ValidationOptions()
                        .validator(legacyEnrichedPublicationValidator())
                        .validationLevel(com.mongodb.client.model.ValidationLevel.STRICT)
                        .validationAction(com.mongodb.client.model.ValidationAction.ERROR)));
        String receipt = "a".repeat(64);
        Document legacy = new Document("_id", "b".repeat(64)).append("schemaVersion", 1).append("receiptId", receipt)
                .append("rawCommitment", "c".repeat(64)).append("enrichedCommitment", "d".repeat(64))
                .append("eventId", "legacy-enriched-event").append("eventCreatedAt", TestSourceIssuer.NOW.toString())
                .append("expiresAt", Date.from(TestSourceIssuer.NOW.plus(Duration.ofHours(1))))
                .append("state", "PUBLISHED").append("publishedAt", Date.from(TestSourceIssuer.NOW))
                .append("keyId", "enriched-key-1")
                .append("nonce", new org.bson.types.Binary(new byte[12])).append("ciphertext", new org.bson.types.Binary(new byte[17]));
        database.getCollection("source_enriched_publications").insertOne(legacy);
        MongoClient limited = MONGO.consumer(ownerClient, migrationDatabase, true);

        assertThatThrownBy(() -> SourceEnrichedPublicationRecoveryMigration.migrate(limited.getDatabase(migrationDatabase)))
                .isInstanceOf(MongoCommandException.class);
        SourceEnrichedPublicationRecoveryMigration.migrate(database);

        Document preserved = database.getCollection("source_enriched_publications").find(eq("_id", legacy.getString("_id"))).first();
        assertThat(preserved).containsEntry("state", "PUBLISHED").containsEntry("eventId", "legacy-enriched-event")
                .containsEntry("historyState", "PENDING").containsEntry("historyAppliedAt", null)
                .containsEntry("historyAttempts", 0).containsKey("historyNextAttemptAt")
                .doesNotContainKeys("attempts", "leaseToken", "recoveryEpoch", "preparedAt");
        var indexes = database.getCollection("source_enriched_publications").listIndexes().into(new ArrayList<>());
        assertThat(indexes).extracting(row -> row.getString("name"))
                .containsExactlyInAnyOrder("_id_", "enriched_publication_recovery", "enriched_publication_history",
                        "enriched_publication_history_retry");
        database.drop();
    }

    @Test void enrichedRecoveryMigrationRejectsAnUnknownValidator() {
        String migrationDatabase = "enriched_incompatible_" + UUID.randomUUID().toString().replace("-", "");
        var database = ownerClient.getDatabase(migrationDatabase);
        database.createCollection("source_enriched_publications",
                new com.mongodb.client.model.CreateCollectionOptions().validationOptions(new com.mongodb.client.model.ValidationOptions()
                        .validator(new Document("$jsonSchema", new Document("bsonType", "object")))
                        .validationLevel(com.mongodb.client.model.ValidationLevel.STRICT)
                        .validationAction(com.mongodb.client.model.ValidationAction.ERROR)));

        assertThatThrownBy(() -> SourceEnrichedPublicationRecoveryMigration.migrate(database))
                .isInstanceOf(IllegalStateException.class).hasMessage("SOURCE_LINEAGE_SCHEMA_INCOMPATIBLE");
        database.drop();
    }

    @Test void businessIdentityMigrationIsOwnerOnlyAndInitializesAnEmptyFenceBeforeEnricherStartup() {
        var database = ownerClient.getDatabase(db);
        var identities = database.getCollection("source_enriched_business_identities");
        assertThat(identities.find(eq("_id", "0".repeat(64))).first())
                .containsEntry("schemaVersion", 1).containsEntry("recordType", "READY").containsEntry("backfilledRows", 0);

        Document collection = database.listCollections().filter(eq("name", "source_enriched_business_identities")).first();
        Document options = collection.get("options", Document.class);
        assertThat(options.getString("validationLevel")).isEqualTo("strict");
        assertThat(options.getString("validationAction")).isEqualTo("error");
        assertThat(options.get("validator", Document.class).get("$jsonSchema", Document.class)
                .getList("oneOf", Document.class)).hasSize(2);
        List<Document> indexes = identities.listIndexes().into(new ArrayList<>());
        assertThat(indexes).extracting(row -> row.getString("name"))
                .containsExactlyInAnyOrder("_id_", "source_business_identity_expiry");
        assertThat(indexes.stream().filter(row -> "source_business_identity_expiry".equals(row.getString("name")))
                .findFirst().orElseThrow()).containsEntry("expireAfterSeconds", 0);

        identities.drop();
        var limitedDatabase = enricherClient.getDatabase(db);
        assertThatThrownBy(() -> SourceEnrichedBusinessIdentityMigration.migrate(limitedDatabase,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)), CLOCK))
                .isInstanceOf(MongoCommandException.class);
        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_INDEX_INVALID");

        assertThatCode(() -> SourceEnrichedBusinessIdentityMigration.migrate(database,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)), CLOCK))
                .doesNotThrowAnyException();
        assertThatCode(() -> newEnricher(CLOCK, verifier)).doesNotThrowAnyException();

        assertThatThrownBy(() -> limitedDatabase.getCollection("source_enriched_business_identities")
                .createIndex(new Document("probe", 1))).isInstanceOf(MongoCommandException.class);
        assertThatThrownBy(() -> limitedDatabase.getCollection("source_enriched_business_identities")
                .updateOne(eq("_id", "0".repeat(64)), new Document("$set", new Document("backfilledRows", 1))))
                .isInstanceOf(MongoCommandException.class);
        assertThatThrownBy(() -> limitedDatabase.getCollection("source_enriched_business_identities")
                .deleteOne(eq("_id", "0".repeat(64)))).isInstanceOf(MongoCommandException.class);
        assertThatThrownBy(() -> scorerClient.getDatabase(db).getCollection("source_enriched_business_identities")
                .countDocuments()).isInstanceOf(MongoCommandException.class);
    }

    @Test void businessIdentityMigrationBackfillsTenPublicationsIdempotentlyAndSeparatesSourceScopes() throws Exception {
        var base = TestSourceIssuer.policy(0, false);
        var otherInstitution = new SourceVerificationProperties.TrustedKey("second-test-issuer", "institution-b",
                base.source(), base.sourceEpoch(), base.signingKeyVersion(), base.algorithm(), base.publicKeyBase64(),
                base.notBefore(), base.notAfter(), false, base.purpose(), base.audience(), base.policyVersion());
        var nextEpoch = new SourceVerificationProperties.TrustedKey(base.issuer(), base.institution(), base.source(),
                "epoch-b", base.signingKeyVersion(), base.algorithm(), base.publicKeyBase64(), base.notBefore(),
                base.notAfter(), false, base.purpose(), base.audience(), base.policyVersion());
        verifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(base, otherInstitution, nextEpoch)), CLOCK);
        owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, owner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        var cipher = new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY));
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK, cipher);

        List<TransactionRawEvent> admitted = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TransactionRawEvent raw = admit(payload("migration-txn-" + i, TestSourceIssuer.NOW.plusSeconds(i),
                    Integer.toString(10 + i) + ".00", "migration-merchant-" + i),
                    Map.of("sourceEventId", "migration-source-" + i));
            enrich(raw);
            admitted.add(raw);
        }
        for (Map<String, Object> scope : List.<Map<String, Object>>of(Map.of(), Map.of("issuer", otherInstitution.issuer(),
                "institution", otherInstitution.institution()), Map.of("sourceEpoch", nextEpoch.sourceEpoch()))) {
            int index = admitted.size();
            Map<String, Object> claims = new HashMap<>(scope);
            claims.put("sourceEventId", "migration-shared-source-" + index);
            TransactionRawEvent raw = admit(payload("migration-shared-transaction", TestSourceIssuer.NOW.plusSeconds(100 + index),
                    Integer.toString(30 + index) + ".00", "migration-shared-merchant-" + index),
                    claims);
            enrich(raw);
            admitted.add(raw);
        }
        assertThat(admitted).hasSize(13);
        assertThat(admitted.stream().map(raw -> enricher.raw(raw).source()).map(SourceScope::issuer).distinct()).hasSize(2);
        assertThat(admitted.stream().map(raw -> enricher.raw(raw).source()).map(SourceScope::sourceEpoch).distinct()).hasSize(2);

        ownerClient.getDatabase(db).getCollection("source_enriched_business_identities").drop();
        var database = ownerClient.getDatabase(db);
        SourceEnrichedBusinessIdentityMigration.migrate(database, cipher, CLOCK);
        var identities = database.getCollection("source_enriched_business_identities");
        assertThat(identities.countDocuments(eq("recordType", "FENCE"))).isEqualTo(13L);
        assertThat(identities.find(eq("_id", "0".repeat(64))).first().getInteger("backfilledRows")).isEqualTo(13);

        for (TransactionRawEvent raw : admitted) {
            SourceLineage parent = enricher.raw(raw);
            Document publication = database.getCollection("source_enriched_publications")
                    .find(eq("rawCommitment", parent.eventCommitment())).first();
            assertThat(publication).isNotNull();
            TransactionEnrichedEvent event = cipher.decrypt(publication);
            String businessIdentity = SourceEventCommitment.businessTransaction(parent.source(), event.customerId(), event.transactionId());
            Document fence = identities.find(eq("_id", businessIdentity)).first();
            assertThat(fence).containsEntry("receiptId", parent.receiptId())
                    .containsEntry("rawCommitment", parent.eventCommitment())
                    .containsEntry("enrichedCommitment", SourceEventCommitment.enriched(event));
        }
        List<Document> fencesBeforeRerun = identities.find(eq("recordType", "FENCE")).sort(new Document("_id", 1))
                .into(new ArrayList<>());
        Document readyBeforeRerun = identities.find(eq("_id", "0".repeat(64))).first();
        SourceEnrichedBusinessIdentityMigration.migrate(database, cipher, CLOCK);
        assertThat(identities.find(eq("recordType", "FENCE")).sort(new Document("_id", 1)).into(new ArrayList<>()))
                .containsExactlyElementsOf(fencesBeforeRerun);
        assertThat(identities.find(eq("_id", "0".repeat(64))).first()).isEqualTo(readyBeforeRerun);
    }

    @Test void businessIdentityMigrationDetectsSameScopeReceiptCollisionAndDoesNotPublishReady() throws Exception {
        TransactionRawEvent first = admit(payload("same-business-id", TestSourceIssuer.NOW, "10.00", "merchant-a"),
                Map.of("sourceEventId", "migration-conflict-a"));
        enrich(first);
        ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .deleteOne(eq("rawCommitment", SourceEventCommitment.raw(first)));
        TransactionRawEvent second = admit(payload("same-business-id", TestSourceIssuer.NOW.plusSeconds(30), "99.00", "merchant-b"),
                Map.of("sourceEventId", "migration-conflict-b"));
        SourceLineage firstParent = enricher.raw(first);
        SourceLineage secondParent = enricher.raw(second);
        TransactionEnrichedEvent firstEvent = enrichedCandidate(first);
        TransactionEnrichedEvent secondEvent = enrichedCandidate(second);
        assertThat(firstParent.receiptId()).isNotEqualTo(secondParent.receiptId());
        assertThat(SourceEventCommitment.businessTransaction(firstParent.source(), first.customerId(), first.transactionId()))
                .isEqualTo(SourceEventCommitment.businessTransaction(secondParent.source(), second.customerId(), second.transactionId()));
        assertThat(firstEvent.transactionAmount()).isNotEqualTo(secondEvent.transactionAmount());
        assertThat(firstEvent.merchantInfo().merchantId()).isNotEqualTo(secondEvent.merchantInfo().merchantId());

        var database = ownerClient.getDatabase(db);
        var identities = database.getCollection("source_enriched_business_identities");
        identities.drop();
        var cipher = new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY));
        insertMigrationPublication(database, cipher, first, firstEvent);
        insertMigrationPublication(database, cipher, second, secondEvent);

        assertThatThrownBy(() -> SourceEnrichedBusinessIdentityMigration.migrate(database, cipher, CLOCK))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_BUSINESS_IDENTITY_COLLISION");
        assertThat(identities.countDocuments(eq("recordType", "FENCE"))).isEqualTo(1L);
        assertThat(identities.find(eq("_id", "0".repeat(64))).first()).isNull();
        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_MIGRATION_INCOMPLETE");
    }

    @Test void businessIdentityMigrationResumesAfterPartialBackfillAndEnricherWaitsForReady() throws Exception {
        TransactionRawEvent first = admit(payload("resume-first", TestSourceIssuer.NOW, "10.00", "merchant-a"),
                Map.of("sourceEventId", "migration-resume-a"));
        TransactionRawEvent second = admit(payload("resume-second", TestSourceIssuer.NOW.plusSeconds(30), "20.00", "merchant-b", "customer-2"),
                Map.of("sourceEventId", "migration-resume-b"));
        var database = ownerClient.getDatabase(db);
        var cipher = new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY));
        insertMigrationPublication(database, cipher, first, enrichedCandidate(first));
        insertMigrationPublication(database, cipher, second, enrichedCandidate(second));
        var identities = database.getCollection("source_enriched_business_identities");
        identities.drop();

        List<Document> publications = database.getCollection("source_enriched_publications")
                .find(new Document("expiresAt", new Document("$gt", Date.from(CLOCK.instant()))))
                .sort(new Document("_id", 1)).into(new ArrayList<>());
        assertThat(publications).hasSize(2);
        Document interrupted = publications.get(1);
        database.getCollection("source_enriched_publications").updateOne(eq("_id", interrupted.getString("_id")),
                new Document("$set", new Document("ciphertext", new org.bson.types.Binary(new byte[] {1, 2, 3}))));

        assertThatThrownBy(() -> SourceEnrichedBusinessIdentityMigration.migrate(database, cipher, CLOCK))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_PUBLICATION_UNAVAILABLE");
        assertThat(identities.countDocuments(eq("recordType", "FENCE"))).isEqualTo(1L);
        assertThat(identities.find(eq("_id", "0".repeat(64))).first()).isNull();
        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_MIGRATION_INCOMPLETE");

        database.getCollection("source_enriched_publications").updateOne(eq("_id", interrupted.getString("_id")),
                new Document("$set", new Document("ciphertext", interrupted.get("ciphertext"))));
        SourceEnrichedBusinessIdentityMigration.migrate(database, cipher, CLOCK);
        assertThat(identities.countDocuments(eq("recordType", "FENCE"))).isEqualTo(2L);
        assertThat(identities.find(eq("_id", "0".repeat(64))).first().getInteger("backfilledRows")).isEqualTo(2);
        assertThatCode(() -> newEnricher(CLOCK, verifier)).doesNotThrowAnyException();
    }

    @Test void businessIdentityMigrationSkipsExpiredPublicationsAndDoesNotAdoptThem() throws Exception {
        TransactionRawEvent raw = admit(payload("expired-publication", TestSourceIssuer.NOW, "10.00", "merchant-expired"),
                Map.of("sourceEventId", "migration-expired-publication"));
        var database = ownerClient.getDatabase(db);
        var cipher = new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY));
        insertMigrationPublication(database, cipher, raw, enrichedCandidate(raw));
        database.getCollection("source_enriched_business_identities").drop();

        SourceEnrichedBusinessIdentityMigration.migrate(database, cipher, Clock.offset(CLOCK, Duration.ofHours(3)));
        var identities = database.getCollection("source_enriched_business_identities");
        assertThat(identities.countDocuments(eq("recordType", "FENCE"))).isZero();
        assertThat(identities.find(eq("_id", "0".repeat(64))).first().getInteger("backfilledRows")).isZero();
        assertThat(database.getCollection("source_enriched_publications")
                .countDocuments(eq("rawCommitment", SourceEventCommitment.raw(raw)))).isEqualTo(1L);
    }

    @Test void businessIdentityStartupRejectsMalformedExistingFenceWithoutRepairingIt() throws Exception {
        TransactionRawEvent raw = admit(payload("tampered-business-fence", TestSourceIssuer.NOW, "10.00", "merchant-fence"),
                Map.of("sourceEventId", "migration-fence-tamper"));
        enrich(raw);
        var database = ownerClient.getDatabase(db);
        var identities = database.getCollection("source_enriched_business_identities");
        String businessIdentity = SourceEventCommitment.businessTransaction(enricher.raw(raw).source(), raw.customerId(), raw.transactionId());
        Document originalCollection = database.listCollections().filter(eq("name", "source_enriched_business_identities")).first();
        Document originalOptions = originalCollection.get("options", Document.class);
        Document strictValidator = Document.parse(originalOptions.get("validator", Document.class).toJson());

        database.runCommand(new Document("collMod", "source_enriched_business_identities")
                .append("validator", new Document()).append("validationLevel", "strict").append("validationAction", "error"));
        identities.updateOne(eq("_id", businessIdentity), new Document("$set", new Document("recordType", "CORRUPT")));
        database.runCommand(new Document("collMod", "source_enriched_business_identities")
                .append("validator", strictValidator).append("validationLevel", "strict").append("validationAction", "error"));

        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_FENCE_INVALID");
        assertThat(identities.find(eq("_id", businessIdentity)).first().getString("recordType")).isEqualTo("CORRUPT");
    }

    @Test void businessIdentityStartupRejectsTamperedValidatorIndexAndReadyMetadataWithoutRepairingThem() {
        var database = ownerClient.getDatabase(db);
        var identities = database.getCollection("source_enriched_business_identities");
        Document originalCollection = database.listCollections().filter(eq("name", "source_enriched_business_identities")).first();
        Document originalOptions = originalCollection.get("options", Document.class);
        Document strictValidator = Document.parse(originalOptions.get("validator", Document.class).toJson());

        Document invalidValidator = new Document("$jsonSchema", new Document("bsonType", "object"));
        database.runCommand(new Document("collMod", "source_enriched_business_identities")
                .append("validator", invalidValidator).append("validationLevel", "strict").append("validationAction", "error"));
        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_SCHEMA_INVALID");
        assertThat(database.listCollections().filter(eq("name", "source_enriched_business_identities")).first()
                .get("options", Document.class).get("validator")).isEqualTo(invalidValidator);

        database.runCommand(new Document("collMod", "source_enriched_business_identities")
                .append("validator", strictValidator).append("validationLevel", "strict").append("validationAction", "error"));
        identities.dropIndex("source_business_identity_expiry");
        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_INDEX_INVALID");
        assertThat(identities.listIndexes().into(new ArrayList<>())).extracting(row -> row.getString("name"))
                .containsExactly("_id_");

        identities.createIndex(new Document("expiresAt", 1), new com.mongodb.client.model.IndexOptions()
                .name("source_business_identity_expiry").expireAfter(0L, TimeUnit.SECONDS));
        database.runCommand(new Document("collMod", "source_enriched_business_identities")
                .append("validator", new Document()).append("validationLevel", "strict").append("validationAction", "error"));
        identities.updateOne(eq("_id", "0".repeat(64)), new Document("$set", new Document("backfilledRows", 100_001)));
        database.runCommand(new Document("collMod", "source_enriched_business_identities")
                .append("validator", strictValidator).append("validationLevel", "strict").append("validationAction", "error"));
        assertThatThrownBy(() -> newEnricher(CLOCK, verifier))
                .hasMessage("SOURCE_BUSINESS_IDENTITY_MIGRATION_MIGRATION_INCOMPLETE");
        assertThat(identities.find(eq("_id", "0".repeat(64))).first().getInteger("backfilledRows")).isEqualTo(100_001);
    }

    @Test void enrichedRecoveryMigrationUpgradesThePreviousHistoryValidator() {
        var database = ownerClient.getDatabase(db);
        Document collection = database.listCollections().filter(eq("name", "source_enriched_publications")).first();
        Document options = collection.get("options", Document.class);
        Document previousValidator = Document.parse(options.get("validator", Document.class).toJson());
        Document jsonSchema = previousValidator.get("$jsonSchema", Document.class);
        Document properties = jsonSchema.get("properties", Document.class);
        properties.get("historyState", Document.class).put("enum", List.of("PENDING", "APPLIED"));
        properties.remove("historyAttempts");
        properties.remove("historyNextAttemptAt");
        properties.remove("historyFailureCode");
        database.runCommand(new Document("collMod", "source_enriched_publications").append("validator", previousValidator)
                .append("validationLevel", "strict").append("validationAction", "error"));

        SourceEnrichedPublicationRecoveryMigration.migrate(database);

        Document migrated = database.listCollections().filter(eq("name", "source_enriched_publications")).first()
                .get("options", Document.class).get("validator", Document.class);
        assertThat(migrated.get("$jsonSchema", Document.class).get("properties", Document.class)
                .get("historyState", Document.class).getList("enum", String.class))
                .containsExactly("PENDING", "APPLIED", "QUARANTINED");
    }

    private static Document legacyEnrichedPublicationValidator() {
        java.util.function.Function<Object, Document> type = value -> new Document("bsonType", value);
        Document hash = type.apply("string").append("pattern", "^[0-9a-f]{64}$");
        Document properties = new Document("_id", hash).append("schemaVersion", type.apply("int").append("enum", List.of(1)))
                .append("receiptId", hash).append("rawCommitment", hash).append("enrichedCommitment", hash)
                .append("eventId", type.apply("string").append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
                .append("eventCreatedAt", type.apply("string").append("maxLength", 64)).append("expiresAt", type.apply("date"))
                .append("state", type.apply("string").append("enum", List.of("PENDING", "PUBLISHED")))
                .append("publishedAt", type.apply(List.of("date", "null")))
                .append("keyId", type.apply("string").append("pattern", "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
                .append("nonce", type.apply("binData")).append("ciphertext", type.apply("binData"));
        return new Document("$jsonSchema", type.apply("object").append("additionalProperties", false)
                .append("required", List.copyOf(properties.keySet())).append("properties", properties));
    }

    @Test void signedHttpInputSurvivesActualSerdeEnricherAndScorerContextWithoutPublicProofFields() throws Exception {
        byte[] payload = payload();
        mvc().perform(post("/api/v1/transactions/attested").contentType("application/json")
                        .header("X-Source-Proof", encodedProof(payload, Map.of())).content(payload))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sourceLineage").doesNotExist());
        var raw = lastRaw();
        attestedRawEventIds.add(raw.eventId());
        enrich(raw);
        var enriched = lastEnriched();
        var scored = score(enriched);
        assertThat(scored.context.sourceLineage().state()).isEqualTo(VERIFIED);
        assertThat(scored.context.sourceLineage().source().sourceEventId()).isEqualTo("source-event-42");
        assertThat(raw.eventId()).isNotEqualTo(enriched.eventId()).isNotEqualTo("source-event-42");
        assertThat(enriched.eventId()).isNotEqualTo(scored.event.eventId());
        assertThat(scored.context.sourceLineage().eventCommitment()).isEqualTo(SourceEventCommitment.enriched(enriched));
        assertThat(scored.event.eventContractVersion()).isEqualTo(2);
        for (Object data : List.of(raw, enriched, enriched.featureSnapshot(), scored.event, scored.context,
                FraudScoringRequest.from(enriched, scored.context.sourceLineage()))) {
            assertThat(JSON.writeValueAsString(data)).doesNotContain(TestSourceIssuer.ISSUER, "institution-a", "source-event-42",
                    "sourceLineage", "payloadCommitment", "signature", TestSourceIssuer.publicKey(0));
        }
        assertThat(metrics.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream()).map(Object::toString))
                .noneMatch(value -> value.contains("institution-a") || value.contains("source-event-42"));
        for (String collection : List.of("source_lineage_raw", "source_lineage_enriched")) {
            String row = ownerClient.getDatabase(db).getCollection(collection).find().first().toJson();
            assertThat(row).doesNotContain("customer-1", "account-1", "transactionAmount", "signature", TestSourceIssuer.ISSUER,
                    "institution-a", "source-event-42", "203.0.113.24", TestSourceIssuer.publicKey(0));
        }
        String publication = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find().first().toJson();
        assertThat(publication).doesNotContain("customer-1", "account-1", "transactionAmount", "institution-a", "source-event-42");
    }

    @Test void ordinaryUnsignedInputKeepsBaselineScoresAndNeverAcquiresSourceAuthority() throws Exception {
        var request = JSON.readValue(payload(), IngestTransactionRequest.class);
        new TransactionIngestService(new TransactionRequestMapper(), publisher(), new KafkaTopicProperties("transactions.raw"),
                new CorrelationIdProvider()).ingest(request);
        var raw = lastRaw();
        assertThat(enricher.raw(raw).state()).isEqualTo(UNVERIFIED);
        enrich(raw);
        var enriched = lastEnriched();
        var result = score(enriched);
        assertThat(result.context.sourceLineage().state()).isEqualTo(UNVERIFIED);
        var baseline = new RuleBasedFraudScoringEngine(scoringProperties()).score(FraudScoringRequest.from(enriched));
        assertThat(result.event.fraudScore()).isEqualTo(baseline.fraudScore());
        assertThat(result.event.reasonCodes()).isEqualTo(baseline.reasonCodes());
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isZero();
    }

    @Test void wrongInstitutionTamperedOriginalAndMissingProofCannotPublishOrFallBack() throws Exception {
        byte[] payload = payload();
        mvc().perform(post("/api/v1/transactions/attested").contentType("application/json").content(payload))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/v1/transactions/attested").contentType("application/json")
                        .header("X-Source-Proof", encodedProof(payload, Map.of("institution", "institution-b"))).content(payload))
                .andExpect(status().isForbidden());
        mvc().perform(post("/api/v1/transactions/attested").contentType("application/json")
                        .header("X-Source-Proof", encodedProof(payload, Map.of())).content(new String(payload, StandardCharsets.UTF_8).replace("42.00", "43.00")))
                .andExpect(status().isForbidden());
        assertThat(rawWire).isEmpty();
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isZero();
    }

    @Test void signedButAmbiguousOrInvalidBusinessJsonIsRejectedBeforeReceiptAdmission() throws Exception {
        for (String body : List.of("null", "{}", new String(payload(), StandardCharsets.UTF_8).replace("\"transactionId\":", "\"transactionId\":\"duplicate\",\"transactionId\":"),
                new String(payload(), StandardCharsets.UTF_8).replaceFirst("\\{", "{\"sourceVerified\":true,"),
                new String(payload(), StandardCharsets.UTF_8) + " {}")) {
            byte[] input = body.getBytes(StandardCharsets.UTF_8);
            mvc().perform(post("/api/v1/transactions/attested").contentType("application/json")
                            .header("X-Source-Proof", encodedProof(input, Map.of())).content(input))
                    .andExpect(status().isBadRequest());
        }
        assertThat(rawWire).isEmpty();
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isZero();
    }

    @Test void changedReceiverIdsKnownFieldsOrFeaturesCannotStealAnExistingBinding() throws Exception {
        var raw = admit(Map.of());
        assertThat(enricher.raw(raw).state()).isEqualTo(VERIFIED);
        assertThat(enricher.raw(change(raw, "eventId", "copied-event", TransactionRawEvent.class)).state()).isEqualTo(UNVERIFIED);
        assertThat(enricher.raw(change(raw, "customerId", "other-customer", TransactionRawEvent.class)).state()).isEqualTo(CONFLICT);
        enrich(raw);
        var enriched = lastEnriched();
        assertThat(scorer.enriched(change(enriched, "eventId", "copied-event", TransactionEnrichedEvent.class)).state()).isEqualTo(UNVERIFIED);
        assertThat(scorer.enriched(change(enriched, "transactionId", "other-transaction", TransactionEnrichedEvent.class)).state()).isEqualTo(CONFLICT);
        ObjectNode fields = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(enriched));
        ((ObjectNode) fields.get("featureSnapshot")).put("deviceNovelty", false);
        assertThat(scorer.enriched(JSON.treeToValue(fields, TransactionEnrichedEvent.class)).state()).isEqualTo(CONFLICT);
        assertThat(scorer.enriched(enriched).state()).isEqualTo(VERIFIED);
    }

    @Test void enrichedPublicationRetryReusesTheExactBoundSnapshotAcrossChangedFeatureHistory() throws Exception {
        var raw = admit(Map.of());
        var snapshot = new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
        var features = new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw, snapshot);
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw, features);

        var prepared = enricher.prepareEnrichedPublication(raw, candidate);
        assertThat(prepared.authority().state()).isEqualTo(VERIFIED);
        assertThat(prepared.event()).isNotNull();
        assertThat(prepared.published()).isFalse();

        Map<String, Object> changedFeatures = new HashMap<>(candidate.featureSnapshot());
        changedFeatures.put(com.frauddetection.common.events.features.FraudFeatureContract.RECENT_TRANSACTION_COUNT, 999);
        var changedCandidate = new TransactionEnrichedEvent(candidate.eventId(), candidate.transactionId(), candidate.correlationId(),
                candidate.customerId(), candidate.accountId(), candidate.createdAt(), candidate.transactionTimestamp(),
                candidate.transactionAmount(), candidate.merchantInfo(), candidate.deviceInfo(), candidate.locationInfo(),
                candidate.customerContext(), changedFeatures);
        var replay = enricher.prepareEnrichedPublication(raw, changedCandidate);
        assertThat(replay.authority().state()).isEqualTo(VERIFIED);
        assertThat(SourceEventCommitment.enriched(replay.event())).isEqualTo(SourceEventCommitment.enriched(candidate));
        assertThat(replay.published()).isFalse();

        var claim = enricher.claimEnrichedPublication(raw, candidate);
        assertThat(claim.status()).isEqualTo(SourceEnrichedPublicationClaim.Status.CLAIMED);
        assertThat(enricher.claimEnrichedPublication(raw, candidate).status()).isEqualTo(SourceEnrichedPublicationClaim.Status.BUSY);
        assertThat(enricher.markEnrichedHistoryApplied(claim).state()).isEqualTo(VERIFIED);
        assertThat(enricher.markEnrichedPublished(claim).state()).isEqualTo(VERIFIED);
        var acknowledged = enricher.prepareEnrichedPublication(raw, changedCandidate);
        assertThat(acknowledged.authority().state()).isEqualTo(VERIFIED);
        assertThat(acknowledged.published()).isTrue();
        assertThat(SourceEventCommitment.enriched(acknowledged.event())).isEqualTo(SourceEventCommitment.enriched(candidate));
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_enriched").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .find().first().getString("state")).isEqualTo("PUBLISHED");
    }

    @Test void enrichedRecoveryHealthIncludesPublishedRowsAwaitingHistoryFinalization() throws Exception {
        var raw = admit(Map.of());
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        enricher.prepareEnrichedPublication(raw, candidate);
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        String publicationId = publications.find().first().getString("_id");
        publications.updateOne(eq("_id", publicationId), new org.bson.Document("$set", new org.bson.Document("state", "PUBLISHED")
                .append("publishedAt", Date.from(TestSourceIssuer.NOW))));

        var pending = enricher.enrichedPublicationHealth();
        assertThat(pending.pending()).isEqualTo(1);
        assertThat(pending.leased()).isZero();
        var historyClaim = enricher.claimNextEnrichedPublications(1).getFirst();
        assertThat(historyClaim.published()).isTrue();
        var leased = enricher.enrichedPublicationHealth();
        assertThat(leased.pending()).isZero();
        assertThat(leased.leased()).isEqualTo(1);
        assertThat(enricher.markEnrichedHistoryApplied(historyClaim).state()).isEqualTo(VERIFIED);
        var complete = enricher.enrichedPublicationHealth();
        assertThat(complete.pending()).isZero();
        assertThat(complete.leased()).isZero();
    }

    @Test void enrichedRecoveryLeaseFencesLiveAndRestartedWorkersAndReusesTheSameEvent() throws Exception {
        var raw = admit(Map.of());
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        var prepared = enricher.prepareEnrichedPublication(raw, candidate);
        var liveClaim = enricher.claimEnrichedPublication(raw, prepared.event());
        assertThat(liveClaim.status()).isEqualTo(SourceEnrichedPublicationClaim.Status.CLAIMED);
        assertThat(enricher.claimNextEnrichedPublications(1)).isEmpty();

        var restarted = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(31)), policy(false));
        var recoveryClaims = restarted.claimNextEnrichedPublications(1);
        assertThat(recoveryClaims).hasSize(1);
        var recoveryClaim = recoveryClaims.getFirst();
        assertThat(recoveryClaim.event()).isEqualTo(liveClaim.event());
        assertThat(SourceEventCommitment.enriched(recoveryClaim.event())).isEqualTo(SourceEventCommitment.enriched(candidate));
        assertThat(enricher.markEnrichedPublished(liveClaim).state()).isNotEqualTo(VERIFIED);
        assertThat(restarted.markEnrichedHistoryApplied(recoveryClaim).state()).isEqualTo(VERIFIED);
        assertThat(restarted.markEnrichedPublished(recoveryClaim).state()).isEqualTo(VERIFIED);
        assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_publications").find().first())
                .containsEntry("state", "PUBLISHED").doesNotContainKeys("leaseToken", "leaseUntil");
    }

    @Test void expiredEnrichedClaimsCannotFinalizeOrSendAfterAnotherWorkerTakesOver() throws Exception {
        var beforeHistoryRaw = admit(payload("lease-before-history-tx", TestSourceIssuer.NOW),
                Map.of("sourceEventId", "lease-before-history"));
        var beforeHistoryCandidate = enrichedCandidate(beforeHistoryRaw);
        var beforeHistoryPrepared = enricher.prepareEnrichedPublication(beforeHistoryRaw, beforeHistoryCandidate);

        var afterHistoryRaw = admit(payload("lease-after-history-tx", TestSourceIssuer.NOW.plus(Duration.ofMinutes(1)),
                "42.00", "merchant-1", "customer-2"),
                Map.of("sourceEventId", "lease-after-history"));
        var afterHistoryCandidate = enrichedCandidate(afterHistoryRaw);
        var afterHistoryPrepared = enricher.prepareEnrichedPublication(afterHistoryRaw, afterHistoryCandidate);

        assertThat(beforeHistoryRaw.transactionId()).isNotEqualTo(afterHistoryRaw.transactionId());
        assertThat(beforeHistoryPrepared.authority().state()).isEqualTo(VERIFIED);
        assertThat(afterHistoryPrepared.authority().state()).isEqualTo(VERIFIED);
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        assertThat(publications.find(eq("rawCommitment", SourceEventCommitment.raw(beforeHistoryRaw))).first())
                .isNotNull();
        assertThat(publications.find(eq("rawCommitment", SourceEventCommitment.raw(afterHistoryRaw))).first())
                .isNotNull();
        assertThat(publications.countDocuments()).isEqualTo(2L);

        var beforeHistoryA = enricher.claimEnrichedPublication(beforeHistoryRaw, beforeHistoryPrepared.event());
        var workerB = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(31)), policy(false));
        var beforeHistoryB = workerB.claimEnrichedPublication(beforeHistoryRaw, beforeHistoryPrepared.event());
        assertThat(beforeHistoryB.status()).isEqualTo(SourceEnrichedPublicationClaim.Status.CLAIMED);
        assertThat(enricher.markEnrichedHistoryApplied(beforeHistoryA).state()).isNotEqualTo(VERIFIED);
        assertThat(workerB.markEnrichedHistoryApplied(beforeHistoryB).state()).isEqualTo(VERIFIED);
        assertThat(enricher.renewEnrichedPublicationLeaseForSend(beforeHistoryA, 10_000L)).isFalse();
        assertThat(workerB.renewEnrichedPublicationLeaseForSend(beforeHistoryB, 10_000L)).isTrue();
        assertThat(workerB.markEnrichedPublished(beforeHistoryB).state()).isEqualTo(VERIFIED);

        var afterHistoryA = enricher.claimEnrichedPublication(afterHistoryRaw, afterHistoryPrepared.event());
        assertThat(enricher.markEnrichedHistoryApplied(afterHistoryA).state()).isEqualTo(VERIFIED);
        var recoveryWorker = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(31)), policy(false));
        var afterHistoryB = recoveryWorker.claimEnrichedPublication(afterHistoryRaw, afterHistoryPrepared.event());
        assertThat(afterHistoryB.status()).isEqualTo(SourceEnrichedPublicationClaim.Status.CLAIMED);
        assertThat(afterHistoryB.historyApplied()).isTrue();
        assertThat(enricher.renewEnrichedPublicationLeaseForSend(afterHistoryA, 10_000L)).isFalse();
        enricher.deferEnrichedPublication(afterHistoryA);
        Document leased = ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .find(eq("_id", afterHistoryB.publicationId())).first();
        assertThat(leased.getString("leaseToken")).isEqualTo(afterHistoryB.claimToken());
        assertThat(leased.getInteger("attempts")).isEqualTo(afterHistoryB.attempts());
        assertThat(recoveryWorker.renewEnrichedPublicationLeaseForSend(afterHistoryB, 10_000L)).isTrue();
        assertThat(recoveryWorker.markEnrichedPublished(afterHistoryB).state()).isEqualTo(VERIFIED);
        Document published = ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .find(eq("_id", afterHistoryB.publicationId())).first();
        assertThat(published).containsEntry("state", "PUBLISHED").containsEntry("historyState", "APPLIED");
    }

    @Test void lateSendAfterSuccessfulLeaseRenewalCanDuplicateOnlyTheSameCanonicalEvent() throws Exception {
        var raw = admit(Map.of("sourceEventId", "lease-renewal-late-send"));
        var history = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        FeatureStore featureStore = new FeatureStore() {
            @Override public boolean hasRecordedTransaction(TransactionRawEvent event) { return false; }
            @Override public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) { return false; }
            @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) {
                return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
            }
            @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
            }
            @Override public void recordTransaction(TransactionRawEvent event) { }
            @Override public void recordAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                            String commitment) { history.add(commitment); }
            @Override public void verifyAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                            String commitment) {
                if (!history.contains(commitment)) { throw new IllegalStateException("FEATURE_HISTORY_REBUILD_REQUIRED"); }
            }
        };
        var pauseBeforeSend = new CountDownLatch(1);
        var resumeLateSend = new CountDownLatch(1);
        List<byte[]> brokerAccepts = Collections.synchronizedList(new ArrayList<>());
        TransactionEnrichedEventPublisher workerAPublisher = new TransactionEnrichedEventPublisher() {
            private void send(TransactionEnrichedEvent event) {
                pauseBeforeSend.countDown();
                awaitLatch(resumeLateSend);
                brokerAccepts.add(serialize(event));
            }
            @Override public void publish(TransactionEnrichedEvent event) { send(event); }
            @Override public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) { send(event); }
        };
        TransactionEnrichedEventPublisher workerBPublisher = event -> brokerAccepts.add(serialize(event));
        var serviceA = new TransactionFeatureEnricherService(featureStore,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                workerAPublisher, enricher, ORIGIN_PROOFS);
        var workerB = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(31)), verifier);
        var serviceB = new TransactionFeatureEnricherService(featureStore,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                workerBPublisher, workerB, ORIGIN_PROOFS);
        String rawProof = ORIGIN_PROOFS.signRaw(raw, SourceOriginProofs.Assertion.KNOWN_ATTESTED);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var firstWorker = pool.submit(() -> serviceA.enrich(raw, rawProof));
            boolean enteredSend = pauseBeforeSend.await(10, TimeUnit.SECONDS);
            if (!enteredSend) { firstWorker.get(1, TimeUnit.SECONDS); }
            assertThat(enteredSend).as("worker A should reach the publisher after renewing its Mongo lease").isTrue();
            var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
            Document afterRenewal = publications.find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first();
            assertThat(afterRenewal).containsEntry("state", "PUBLISHING").containsEntry("historyState", "APPLIED");
            assertThat(afterRenewal.getDate("leaseUntil").toInstant()).isEqualTo(TestSourceIssuer.NOW.plusSeconds(30));

            serviceB.enrich(raw, rawProof);
            assertThat(brokerAccepts).hasSize(1);
            Document publishedByB = publications.find(eq("_id", afterRenewal.getString("_id"))).first();
            assertThat(publishedByB).containsEntry("state", "PUBLISHED").containsEntry("historyState", "APPLIED")
                    .doesNotContainKeys("leaseToken", "leaseUntil");
            Date publishedAt = publishedByB.getDate("publishedAt");

            // Mongo and Kafka cannot share a transaction: A's late broker send is an allowed at-least-once duplicate.
            // The immutable publication, receipt, event bytes, and business identity stay the same.
            resumeLateSend.countDown();
            firstWorker.get(10, TimeUnit.SECONDS);
            Document afterLateAck = publications.find(eq("_id", afterRenewal.getString("_id"))).first();
            assertThat(afterLateAck.getDate("publishedAt")).isEqualTo(publishedAt);
            assertThat(afterLateAck.getString("enrichedCommitment")).isEqualTo(SourceEventCommitment.enriched(
                    new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY))
                            .decrypt(afterLateAck)));
            assertThat(brokerAccepts).hasSize(2);
            assertThat(brokerAccepts.get(0)).isEqualTo(brokerAccepts.get(1));
            assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_business_identities")
                    .countDocuments(eq("rawCommitment", SourceEventCommitment.raw(raw)))).isEqualTo(1);
            assertThat(afterLateAck.getString("_id")).isEqualTo(afterRenewal.getString("_id"));
            assertThat(afterLateAck.getString("rawCommitment")).isEqualTo(SourceEventCommitment.raw(raw));
            assertThat(afterLateAck.getString("receiptId")).isEqualTo(enricher.raw(raw).receiptId());
        } finally {
            resumeLateSend.countDown();
        }
    }

    @Test void realRawListenerDltAndIndependentRecoveryDrainTheOriginalEnrichedOutbox() throws Exception {
        FraudPlatformContainers.startAll();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String rawTopic = "source-raw-" + suffix;
        String enrichedTopic = "source-enriched-" + suffix;
        String dltTopic = "source-dlt-" + suffix;
        try (AdminClient admin = AdminClient.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                FraudPlatformContainers.kafka().getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(rawTopic, 1, (short) 1),
                    new NewTopic(enrichedTopic, 1, (short) 1), new NewTopic(dltTopic, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }

        LettuceConnectionFactory redisFactory = new LettuceConnectionFactory(
                FraudPlatformContainers.redis().getHost(), FraudPlatformContainers.redis().getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        StringRedisTemplate realRedis = new StringRedisTemplate(redisFactory);
        realRedis.afterPropertiesSet();
        realRedis.getConnectionFactory().getConnection().serverCommands().flushAll();

        Map<String, Object> producerProperties = new HashMap<>();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers());
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonKafkaSerializer.class);
        var rawFactory = new DefaultKafkaProducerFactory<String, TransactionRawEvent>(producerProperties,
                new StringSerializer(), new JacksonKafkaSerializer<>());
        var enrichedFactory = new DefaultKafkaProducerFactory<String, TransactionEnrichedEvent>(producerProperties,
                new StringSerializer(), new JacksonKafkaSerializer<>());
        var dltFactory = new DefaultKafkaProducerFactory<Object, Object>(producerProperties);
        KafkaTemplate<String, TransactionRawEvent> rawTemplate = new KafkaTemplate<>(rawFactory);
        KafkaTemplate<String, TransactionEnrichedEvent> enrichedTemplate = new KafkaTemplate<>(enrichedFactory);
        KafkaTemplate<Object, Object> dltTemplate = new KafkaTemplate<>(dltFactory);

        AtomicBoolean failFirstEnrichedSend = new AtomicBoolean(true);
        AtomicInteger publisherCalls = new AtomicInteger();
        AtomicInteger serviceCalls = new AtomicInteger();
        AtomicReference<byte[]> firstAttemptBytes = new AtomicReference<>();
        List<byte[]> successfulBytes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch successfulSends = new CountDownLatch(1);
        AtomicReference<String> rawEventToAwait = new AtomicReference<>();
        CountDownLatch awaitedRawProcessed = new CountDownLatch(1);
        TransactionEnrichedEventPublisher realKafkaPublisher = new TransactionEnrichedEventPublisher() {
            @Override public void publish(TransactionEnrichedEvent event) { send(event, null); }
            @Override public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) { send(event, origin); }
            private void send(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) {
                publisherCalls.incrementAndGet();
                byte[] bytes = serialize(event);
                if (failFirstEnrichedSend.compareAndSet(true, false)) {
                    firstAttemptBytes.set(bytes);
                    throw new IllegalStateException("controlled ENRICHED broker boundary failure");
                }
                ProducerRecord<String, TransactionEnrichedEvent> record = new ProducerRecord<>(enrichedTopic,
                        event.transactionId(), event);
                if (origin != null) {
                    String proof = ORIGIN_PROOFS.signEnriched(event, origin);
                    record.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER,
                            proof.getBytes(StandardCharsets.US_ASCII)));
                    enrichedOriginProofs.put(event.eventId(), proof);
                }
                try {
                    enrichedTemplate.send(record).get(10, TimeUnit.SECONDS);
                    successfulBytes.add(bytes);
                    successfulSends.countDown();
                } catch (Exception failure) {
                    throw new IllegalStateException("controlled ENRICHED broker send failed", failure);
                }
            }
        };

        FeatureStore realFeatureStore = new RedisFeatureStore(realRedis,
                new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8),
                        Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
        var realEnricher = new TransactionFeatureEnricherService(realFeatureStore,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                realKafkaPublisher, enricher, ORIGIN_PROOFS);
        TransactionFeatureEnrichmentUseCase countedEnricher = new TransactionFeatureEnrichmentUseCase() {
            @Override public void enrich(TransactionRawEvent event) { realEnricher.enrich(event); }
            @Override public void enrich(TransactionRawEvent event, String proof) {
                serviceCalls.incrementAndGet();
                realEnricher.enrich(event, proof);
                if (event.eventId().equals(rawEventToAwait.get())) { awaitedRawProcessed.countDown(); }
            }
        };
        TransactionRawEventListener listener = new TransactionRawEventListener(countedEnricher,
                new com.frauddetection.enricher.config.KafkaTopicProperties(rawTopic, enrichedTopic, dltTopic));

        Map<String, Object> rawConsumerProperties = new HashMap<>();
        rawConsumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers());
        rawConsumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "source-enricher-" + suffix);
        rawConsumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        rawConsumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var rawConsumerFactory = new DefaultKafkaConsumerFactory<String, TransactionRawEvent>(rawConsumerProperties,
                new StringDeserializer(), new JacksonKafkaDeserializer<>(TransactionRawEvent.class));
        var errorHandler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(dltTemplate,
                (record, failure) -> new TopicPartition(dltTopic, record.partition())), new FixedBackOff(0L, 2L));
        var listenerFactory = new ConcurrentKafkaListenerContainerFactory<String, TransactionRawEvent>();
        listenerFactory.setConsumerFactory(rawConsumerFactory);
        listenerFactory.setCommonErrorHandler(errorHandler);
        listenerFactory.setConcurrency(1);
        var container = listenerFactory.createContainer(rawTopic);
        container.getContainerProperties().setGroupId("source-enricher-" + suffix);
        container.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        container.setupMessageListener((MessageListener<String, TransactionRawEvent>) record -> {
            var proofHeader = record.headers().lastHeader(SourceOriginProofs.KAFKA_HEADER);
            String proof = proofHeader == null ? null : new String(proofHeader.value(), StandardCharsets.US_ASCII);
            listener.onMessage(record.value(), null, proof);
        });

        try {
            var raw = admit(Map.of("sourceEventId", "kafka-recovery-" + suffix));
            container.start();
            ProducerRecord<String, TransactionRawEvent> rawRecord = new ProducerRecord<>(rawTopic, customerPartitionKey(raw.customerId()), raw);
            rawRecord.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER,
                    ORIGIN_PROOFS.signRaw(raw, SourceOriginProofs.Assertion.KNOWN_ATTESTED)
                            .getBytes(StandardCharsets.US_ASCII)));
            rawTemplate.send(rawRecord).get(10, TimeUnit.SECONDS);

            ConsumerRecord<String, TransactionRawEvent> deadLetter = pollKafkaRecord(
                    dltTopic, TransactionRawEvent.class, Duration.ofSeconds(25));
            assertThat(deadLetter.value()).isEqualTo(raw);
            assertThat(serviceCalls.get()).isGreaterThanOrEqualTo(3);
            assertThat(publisherCalls.get()).isEqualTo(1);
            container.stop();

            var initialOutbox = enricher.findEnrichedPublication(raw).publication();
            assertThat(initialOutbox).isNotNull();
            assertThat(initialOutbox.historyApplied()).isTrue();
            assertThat(initialOutbox.published()).isFalse();
            TransactionEnrichedEvent canonical = initialOutbox.event();
            FeatureNamespace namespace = FeatureNamespace.attested(enricher.raw(raw));
            Set<String> historyKeys = realRedis.keys("feature:v3:attested:*");
            assertThat(historyKeys).hasSize(6);
            assertThat(realRedis.opsForZSet().zCard(historyKeys.stream().filter(k -> k.endsWith(":customer:transactions")).findFirst().orElseThrow()))
                    .isEqualTo(1L);
            assertThat(realRedis.opsForZSet().zCard(historyKeys.stream().filter(k -> k.endsWith(":transactions") && k.contains(":merchant:")).findFirst().orElseThrow()))
                    .isEqualTo(1L);
            assertThat(realRedis.opsForHash().size(historyKeys.stream().filter(k -> k.endsWith(":customer:processed-transactions")).findFirst().orElseThrow()))
                    .isEqualTo(1L);
            assertThat(realRedis.opsForSet().size(historyKeys.stream().filter(k -> k.endsWith(":customer:transaction-ids")).findFirst().orElseThrow()))
                    .isEqualTo(1L);

            // Advance the customer's one-minute velocity window with a distinct, authenticated observation.
            byte[] laterPayload = payload(raw.transactionId() + "-later", raw.transactionTimestamp().plus(Duration.ofMinutes(2)));
            TransactionRawEvent laterRaw = admit(laterPayload, Map.of("sourceEventId", "kafka-recovery-later-" + suffix));
            container.start();
            ProducerRecord<String, TransactionRawEvent> laterRecord = new ProducerRecord<>(rawTopic,
                    customerPartitionKey(laterRaw.customerId()), laterRaw);
            laterRecord.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER,
                    ORIGIN_PROOFS.signRaw(laterRaw, SourceOriginProofs.Assertion.KNOWN_ATTESTED)
                            .getBytes(StandardCharsets.US_ASCII)));
            rawTemplate.send(laterRecord).get(10, TimeUnit.SECONDS);
            assertThat(successfulSends.await(20, TimeUnit.SECONDS)).isTrue();
            container.stop();

            var laterOutbox = enricher.findEnrichedPublication(laterRaw).publication();
            assertThat(laterOutbox).isNotNull();
            assertThat(laterOutbox.historyApplied()).isTrue();
            assertThat(laterOutbox.published()).isTrue();
            historyKeys = realRedis.keys("feature:v3:attested:*");
            assertThat(realRedis.opsForZSet().zCard(historyKeys.stream().filter(k -> k.endsWith(":customer:transactions")).findFirst().orElseThrow()))
                    .isEqualTo(1L);
            assertThat(realRedis.opsForZSet().zCard(historyKeys.stream().filter(k -> k.endsWith(":transactions") && k.contains(":merchant:")).findFirst().orElseThrow()))
                    .isEqualTo(2L);
            assertThat(realRedis.opsForHash().size(historyKeys.stream().filter(k -> k.endsWith(":customer:processed-transactions")).findFirst().orElseThrow()))
                    .isEqualTo(2L);
            assertThat(realRedis.opsForSet().size(historyKeys.stream().filter(k -> k.endsWith(":customer:transaction-ids")).findFirst().orElseThrow()))
                    .isEqualTo(2L);

            int callsAtDlt = serviceCalls.get();
            Document pendingRecovery = ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                    .find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first();
            assertThat(pendingRecovery.getString("state")).isEqualTo("PENDING");
            assertThat(pendingRecovery.getDate("nextAttemptAt").toInstant())
                    .isBeforeOrEqualTo(CLOCK.instant().plusSeconds(5));
            realFeatureStore.verifyAttestedTransaction(canonical, namespace,
                    SourceEventCommitment.attestedHistory(enricher.raw(raw).receiptId(), canonical));
            var independentRecovery = new SourceEnrichedPublicationRecovery(newEnricher(
                    Clock.offset(CLOCK, Duration.ofSeconds(5)), policy(false)), realKafkaPublisher, realFeatureStore,
                    new SourceEnrichedPublicationRecoveryProperties(true, 1, 0, 1_000));
            independentRecovery.recover();
            assertThat(serviceCalls.get()).isEqualTo(callsAtDlt);
            Document recoveredRow = ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                    .find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first();
            assertThat(publisherCalls.get()).as("recovery row state=%s attempts=%s nextAttemptAt=%s historyState=%s failureCode=%s",
                    recoveredRow.getString("state"), recoveredRow.getInteger("attempts"), recoveredRow.getDate("nextAttemptAt"),
                    recoveredRow.getString("historyState"), recoveredRow.getString("failureCode")).isEqualTo(3);

            List<ConsumerRecord<String, TransactionEnrichedEvent>> published = pollKafkaRecords(
                    enrichedTopic, TransactionEnrichedEvent.class, 2, Duration.ofSeconds(15));
            assertThat(published).extracting(ConsumerRecord::value).containsExactly(laterOutbox.event(), canonical);
            assertThat(successfulBytes).hasSize(2);
            assertThat(successfulBytes.get(1)).containsExactly(firstAttemptBytes.get());
            var stored = enricher.findEnrichedPublication(raw).publication();
            assertThat(stored.published()).isTrue();
            assertThat(SourceEventCommitment.enriched(stored.event())).isEqualTo(SourceEventCommitment.enriched(canonical));

            // Replaying the exact RAW A after B pruned its minute-window membership remains idempotent.
            container.start();
            rawEventToAwait.set(raw.eventId());
            rawRecord = new ProducerRecord<>(rawTopic, customerPartitionKey(raw.customerId()), raw);
            rawRecord.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER,
                    ORIGIN_PROOFS.signRaw(raw, SourceOriginProofs.Assertion.KNOWN_ATTESTED)
                            .getBytes(StandardCharsets.US_ASCII)));
            rawTemplate.send(rawRecord).get(10, TimeUnit.SECONDS);
            assertThat(awaitedRawProcessed.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(publisherCalls.get()).isEqualTo(3);
            assertThat(successfulBytes).hasSize(2);

            TransactionRawEvent next = nextTransaction(laterRaw);
            FeatureStoreSnapshot nextSnapshot = realFeatureStore.loadSnapshot(next, namespace);
            assertThat(nextSnapshot.recentTransactionCount()).isEqualTo(1);
            assertThat(nextSnapshot.recentAmountSumPln()).isEqualByComparingTo(new CurrencyAmountConverter()
                    .toPln(laterRaw.transactionAmount().amount(), laterRaw.transactionAmount().currency()));
            assertThat(nextSnapshot.merchantFrequency7d()).isEqualTo(2);
            assertThat(nextSnapshot.knownDevice()).isTrue();
            assertThat(nextSnapshot.lastTransactionTimestamp()).isEqualTo(laterRaw.transactionTimestamp());
            Scored scored = score(published.get(1).value());
            assertThat(scored.context.sourceLineage().state()).isEqualTo(VERIFIED);
            assertThat(scored.context.sourceLineage().source().sourceEventId()).isEqualTo("kafka-recovery-" + suffix);

            byte[] latePayload = payload(raw.transactionId() + "-late", raw.transactionTimestamp().plusSeconds(30));
            TransactionRawEvent lateRaw = admit(latePayload, Map.of("sourceEventId", "kafka-recovery-late-" + suffix));
            String lateProof = ORIGIN_PROOFS.signRaw(lateRaw, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            container.start();
            ProducerRecord<String, TransactionRawEvent> lateRecord = new ProducerRecord<>(rawTopic,
                    customerPartitionKey(lateRaw.customerId()), lateRaw);
            lateRecord.headers().add(new RecordHeader(SourceOriginProofs.KAFKA_HEADER, lateProof.getBytes(StandardCharsets.US_ASCII)));
            rawTemplate.send(lateRecord).get(10, TimeUnit.SECONDS);

            List<ConsumerRecord<String, TransactionRawEvent>> deadLetters = pollKafkaRecords(
                    dltTopic, TransactionRawEvent.class, 2, Duration.ofSeconds(25));
            container.stop();
            assertThat(deadLetters).extracting(ConsumerRecord::value).containsExactly(raw, lateRaw);
            ConsumerRecord<String, TransactionRawEvent> lateDeadLetter = deadLetters.getLast();
            var retainedProofHeader = lateDeadLetter.headers().lastHeader(SourceOriginProofs.KAFKA_HEADER);
            assertThat(retainedProofHeader).isNotNull();
            assertThat(ORIGIN_PROOFS.verifyRaw(lateDeadLetter.value(),
                    new String(retainedProofHeader.value(), StandardCharsets.US_ASCII)).assertion())
                    .isEqualTo(SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            var lateDiagnostic = lateDeadLetter.headers().lastHeader("kafka_dlt-exception-message");
            assertThat(lateDiagnostic).isNotNull();
            assertThat(new String(lateDiagnostic.value(), StandardCharsets.UTF_8))
                    .contains("FEATURE_EVENT_TIME_OUT_OF_ORDER");

            SourceLineage lateLineage = enricher.raw(lateRaw);
            String lateGateId = SourceFeatureOrderIdentity.identity(lateLineage.source(), lateRaw.customerId());
            Document lateGate = ownerClient.getDatabase(db).getCollection("source_feature_order_gates")
                    .find(eq("_id", lateGateId)).first();
            assertThat(lateGate).containsEntry("status", "LATE_REJECTED")
                    .containsEntry("receiptId", lateLineage.receiptId())
                    .containsEntry("rawCommitment", SourceEventCommitment.raw(lateRaw));
            assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts")
                    .find(eq("_id", lateLineage.receiptId())).first()).isNotNull();
            assertThat(ownerClient.getDatabase(db).getCollection("source_admission_identities")
                    .find(eq("_id", lateLineage.receiptId())).first()).containsEntry("state", "ADMITTED");
            assertThat(enricher.findEnrichedPublication(lateRaw).publication()).isNull();
            assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_publications").countDocuments()).isEqualTo(2L);
            assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_enriched").countDocuments()).isEqualTo(2L);
            assertThat(ownerClient.getDatabase(db).getCollection("source_scored_occurrences")
                    .countDocuments(eq("receiptId", lateLineage.receiptId()))).isZero();
        } finally {
            container.stop();
            realRedis.getConnectionFactory().getConnection().serverCommands().flushAll();
            redisFactory.destroy();
            rawFactory.destroy();
            enrichedFactory.destroy();
            dltFactory.destroy();
        }
    }

    @Test void durableBusinessIdentityFenceRejectsReusedTransactionIdAfterRedisMarkerLoss() throws Exception {
        FraudPlatformContainers.startAll();
        LettuceConnectionFactory redisFactory = new LettuceConnectionFactory(
                FraudPlatformContainers.redis().getHost(), FraudPlatformContainers.redis().getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        StringRedisTemplate realRedis = new StringRedisTemplate(redisFactory);
        realRedis.afterPropertiesSet();
        realRedis.getConnectionFactory().getConnection().serverCommands().flushAll();
        try {
            FeatureStore realFeatureStore = new RedisFeatureStore(realRedis,
                    new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8),
                            Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
            List<TransactionEnrichedEvent> published = new ArrayList<>();
            TransactionEnrichedEventPublisher publisher = new TransactionEnrichedEventPublisher() {
                @Override public void publish(TransactionEnrichedEvent event) { published.add(event); }
                @Override public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) {
                    published.add(event);
                }
            };
            var realEnricher = new TransactionFeatureEnricherService(realFeatureStore,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, enricher, ORIGIN_PROOFS);

            String suffix = UUID.randomUUID().toString().replace("-", "");
            byte[] payloadA = payload("collision-transaction", TestSourceIssuer.NOW, "100.00", "merchant-a");
            byte[] payloadB = payload("collision-transaction", TestSourceIssuer.NOW.plus(Duration.ofMinutes(2)), "200.00", "merchant-b");
            TransactionRawEvent a = admit(payloadA, Map.of("sourceEventId", "collision-a-" + suffix));
            String proofA = ORIGIN_PROOFS.signRaw(a, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            realEnricher.enrich(a, proofA);
            assertThat(published).hasSize(1);
            TransactionRawEvent b = admit(payloadB, Map.of("sourceEventId", "collision-b-" + suffix));
            String proofB = ORIGIN_PROOFS.signRaw(b, SourceOriginProofs.Assertion.KNOWN_ATTESTED);

            // Simulate upgrade with a pre-existing active canonical outbox and verify owner backfill.
            ownerClient.getDatabase(db).getCollection("source_enriched_business_identities").drop();
            SourceEnrichedPublicationCipher publicationCipher =
                    new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY));
            SourceEnrichedBusinessIdentityMigration.migrate(ownerClient.getDatabase(db), publicationCipher, CLOCK);
            assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_business_identities")
                    .countDocuments(eq("recordType", "FENCE"))).isEqualTo(1L);

            FeatureNamespace namespace = FeatureNamespace.attested(enricher.raw(a));
            Set<String> keys = realRedis.keys("feature:v3:attested:*");
            String commitmentHash = keys.stream().filter(key -> key.endsWith(":customer:processed-transactions")).findFirst().orElseThrow();
            String businessIds = keys.stream().filter(key -> key.endsWith(":customer:transaction-ids")).findFirst().orElseThrow();
            realRedis.delete(List.of(commitmentHash, businessIds));
            assertThat(realFeatureStore.hasRecordedTransaction(b, namespace)).isFalse();

            assertThat(enricher.prepareEnrichedPublication(b, enrichedCandidate(b)).authority().state()).isEqualTo(CONFLICT);
            assertThatThrownBy(() -> realEnricher.enrich(b, proofB))
                    .hasMessage("FEATURE_HISTORY_REBUILD_REQUIRED");
            assertThat(enricher.findEnrichedPublication(b).publication()).isNull();
            assertThat(published).hasSize(1);

            var source = TestSourceIssuer.policy(0, false);
            var otherInstitution = new SourceVerificationProperties.TrustedKey("second-test-issuer", "institution-b", source.source(),
                    source.sourceEpoch(), source.signingKeyVersion(), source.algorithm(), source.publicKeyBase64(),
                    source.notBefore(), source.notAfter(), false, source.purpose(), source.audience(), source.policyVersion());
            verifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(source, otherInstitution)), CLOCK);
            owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
            receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                    new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, owner,
                    new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
            enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK,
                    publicationCipher);
            TransactionRawEvent c = admit(payloadB, Map.of("issuer", otherInstitution.issuer(), "institution", otherInstitution.institution(),
                    "sourceEventId", "collision-c-" + suffix));
            String proofC = ORIGIN_PROOFS.signRaw(c, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            var institutionAwareEnricher = new TransactionFeatureEnricherService(realFeatureStore,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, enricher, ORIGIN_PROOFS);
            assertThatCode(() -> institutionAwareEnricher.enrich(c, proofC)).doesNotThrowAnyException();
            assertThat(published).hasSize(2);
            assertThat(FeatureNamespace.attested(enricher.raw(c))).isNotEqualTo(namespace);
            assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_business_identities")
                    .countDocuments(eq("recordType", "FENCE"))).isEqualTo(2L);

            // The published canonical A is recognized durably even though both Redis identity markers are gone.
            assertThatCode(() -> institutionAwareEnricher.enrich(a, proofA)).doesNotThrowAnyException();
            assertThat(published).hasSize(2);
            assertThat(realRedis.opsForZSet().zCard(keys.stream().filter(key -> key.endsWith(":customer:transactions"))
                    .findFirst().orElseThrow())).isEqualTo(1L);
            List<String> merchantHistory = keys.stream().filter(key -> key.endsWith(":transactions") && key.contains(":merchant:")).toList();
            assertThat(merchantHistory).hasSize(1);
            assertThat(realRedis.opsForZSet().zCard(merchantHistory.getFirst())).isEqualTo(1L);
        } finally {
            realRedis.getConnectionFactory().getConnection().serverCommands().flushAll();
            redisFactory.destroy();
        }
    }

    private <T> ConsumerRecord<String, T> pollKafkaRecord(String topic, Class<T> type, Duration timeout) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "source-poll-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );
        try (KafkaConsumer<String, T> consumer = new KafkaConsumer<>(properties, new StringDeserializer(),
                new JacksonKafkaDeserializer<>(type))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(250));
                if (!records.isEmpty()) { return records.iterator().next(); }
            }
        }
        throw new AssertionError("No Kafka record arrived on " + topic + " within " + timeout);
    }

    private <T> List<ConsumerRecord<String, T>> pollKafkaRecords(String topic, Class<T> type, int count, Duration timeout) {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudPlatformContainers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "source-poll-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );
        try (KafkaConsumer<String, T> consumer = new KafkaConsumer<>(properties, new StringDeserializer(),
                new JacksonKafkaDeserializer<>(type))) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, T>> received = new ArrayList<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && received.size() < count) {
                consumer.poll(Duration.ofMillis(250)).forEach(received::add);
            }
            if (received.size() >= count) { return List.copyOf(received.subList(0, count)); }
        }
        throw new AssertionError("Expected " + count + " Kafka records on " + topic + " within " + timeout);
    }

    private TransactionRawEvent nextTransaction(TransactionRawEvent raw) {
        return new TransactionRawEvent("next-" + raw.eventId(), "next-" + raw.transactionId(), raw.correlationId(),
                raw.customerId(), raw.accountId(), raw.paymentInstrumentId(), raw.createdAt().plusMillis(1),
                raw.transactionTimestamp().plusSeconds(1), raw.transactionAmount(), raw.merchantInfo(), raw.deviceInfo(),
                raw.locationInfo(), raw.customerContext(), raw.transactionType(), raw.authorizationMethod(), raw.sourceSystem(),
                raw.traceId(), raw.attributes());
    }

    private TransactionEnrichedEvent enrichedCandidate(TransactionRawEvent raw) {
        return new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
    }

    private void insertMigrationPublication(com.mongodb.client.MongoDatabase database,
                                            SourceEnrichedPublicationCipher cipher,
                                            TransactionRawEvent raw,
                                            TransactionEnrichedEvent event) {
        SourceLineage parent = enricher.raw(raw);
        assertThat(parent.state()).isEqualTo(VERIFIED);
        Document rawLink = database.getCollection("source_lineage_raw")
                .find(eq("eventCommitment", parent.eventCommitment())).first();
        assertThat(rawLink).isNotNull();
        Date expiresAt = rawLink.getDate("expiresAt");
        Date now = Date.from(CLOCK.instant());
        Document row = new Document("_id", SourceEventCommitment.enriched(event)).append("schemaVersion", 1)
                .append("receiptId", parent.receiptId()).append("rawCommitment", parent.eventCommitment())
                .append("enrichedCommitment", SourceEventCommitment.enriched(event)).append("eventId", event.eventId())
                .append("eventCreatedAt", event.createdAt().toString()).append("expiresAt", expiresAt)
                .append("state", "PENDING").append("publishedAt", null).append("preparedAt", now).append("attempts", 0)
                .append("nextAttemptAt", now).append("recoveryEpoch", 0).append("historyState", "PENDING")
                .append("historyAppliedAt", null).append("historyAttempts", 0).append("historyNextAttemptAt", now);
        cipher.encrypt(row, event);
        database.getCollection("source_enriched_publications").insertOne(row);
    }

    @Test void publishedHistoryPayloadFailureIsQuarantinedWithoutBlockingNextHistoryRow() throws Exception {
        var source = TestSourceIssuer.policy(0, false);
        var nextEpoch = new SourceVerificationProperties.TrustedKey(source.issuer(), source.institution(), source.source(),
                "epoch-b", source.signingKeyVersion(), source.algorithm(), source.publicKeyBase64(), source.notBefore(),
                source.notAfter(), false, source.purpose(), source.audience(), source.policyVersion());
        verifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(source, nextEpoch)), CLOCK);
        owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, owner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
        String damagedId = preparePublishedWithPendingHistory(admit(Map.of()));
        String healthyId = preparePublishedWithPendingHistory(admit(Map.of("sourceEpoch", "epoch-b")));
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        publications.updateOne(eq("_id", damagedId), new Document("$set", new Document("preparedAt", Date.from(TestSourceIssuer.NOW.minusSeconds(10)))
                .append("ciphertext", new org.bson.types.Binary(new byte[] {1, 2, 3}))));
        publications.updateOne(eq("_id", healthyId), new Document("$set", new Document("preparedAt", Date.from(TestSourceIssuer.NOW))));

        assertThat(enricher.claimNextEnrichedPublications(1)).isEmpty();
        assertThat(publications.find(eq("_id", damagedId)).first())
                .containsEntry("state", "PUBLISHED").containsEntry("historyState", "QUARANTINED")
                .containsEntry("failureCode", "PAYLOAD_UNAVAILABLE")
                .doesNotContainKeys("historyLeaseToken", "historyLeaseUntil");
        assertThat(enricher.enrichedPublicationHealth()).satisfies(health -> {
            assertThat(health.pending()).isEqualTo(1);
            assertThat(health.quarantined()).isEqualTo(1);
        });

        var nextCycle = enricher.claimNextEnrichedPublications(1);
        assertThat(nextCycle).hasSize(1);
        assertThat(nextCycle.getFirst().publicationId()).isEqualTo(healthyId);
    }

    @Test void historyRetryBudgetIsIndependentBackedOffAndOperatorReconciliationKeepsExactIntent() throws Exception {
        var failedRaw = admit(payload("history-retry-failed", TestSourceIssuer.NOW),
                Map.of("sourceEventId", "history-retry-failed"));
        var failedPrepared = enricher.prepareEnrichedPublication(failedRaw, enrichedCandidate(failedRaw));
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        String failedId = publications.find(eq("rawCommitment", SourceEventCommitment.raw(failedRaw))).first().getString("_id");

        var unavailableFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        unavailableFactory.afterPropertiesSet();
        var unavailableRedis = new StringRedisTemplate(unavailableFactory);
        unavailableRedis.afterPropertiesSet();
        var unavailableStore = new RedisFeatureStore(unavailableRedis,
                new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8),
                        Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
        var recoveryProperties = new SourceEnrichedPublicationRecoveryProperties(true, 1, 0, 30_000, 5_000);
        try {
            var firstRecovery = new SourceEnrichedPublicationRecovery(newEnricher(CLOCK, verifier), event -> { },
                    unavailableStore, recoveryProperties);
            firstRecovery.recover();
            Document firstFailure = publications.find(eq("_id", failedId)).first();
            assertThat(firstFailure).containsEntry("historyAttempts", 1).containsEntry("attempts", 0)
                    .containsEntry("state", "PENDING").containsEntry("historyState", "PENDING");
            assertThat(firstFailure.getDate("historyNextAttemptAt").toInstant())
                    .isEqualTo(TestSourceIssuer.NOW.plusSeconds(1));

            var healthyRaw = admit(payload("history-retry-healthy", TestSourceIssuer.NOW.plusSeconds(1),
                    "42.00", "merchant-1", "customer-2"),
                    Map.of("sourceEventId", "history-retry-healthy"));
            enricher.prepareEnrichedPublication(healthyRaw, enrichedCandidate(healthyRaw));
            var healthyStore = mock(FeatureStore.class);
            doNothing().when(healthyStore).recordAttestedTransaction(any(), any(), anyString());
            var healthyRecovery = new SourceEnrichedPublicationRecovery(enricher, event -> { }, healthyStore, recoveryProperties);
            healthyRecovery.recover();
            Document healthy = publications.find(eq("rawCommitment", SourceEventCommitment.raw(healthyRaw))).first();
            assertThat(healthy).containsEntry("state", "PUBLISHED").containsEntry("historyState", "APPLIED")
                    .containsEntry("historyAttempts", 0).containsEntry("attempts", 1);
            assertThat(publications.find(eq("_id", failedId)).first()).containsEntry("historyAttempts", 1)
                    .containsEntry("attempts", 0);

            var permanentRaw = admit(payload("history-retry-permanent", TestSourceIssuer.NOW.plusSeconds(2),
                    "42.00", "merchant-1", "customer-3"),
                    Map.of("sourceEventId", "history-retry-permanent"));
            enricher.prepareEnrichedPublication(permanentRaw, enrichedCandidate(permanentRaw));
            var corruptStore = mock(FeatureStore.class);
            doThrow(new com.frauddetection.enricher.exception.FeatureEnrichmentException("FEATURE_HISTORY_REDIS_SCHEMA_CONFLICT"))
                    .when(corruptStore).recordAttestedTransaction(any(), any(), anyString());
            new SourceEnrichedPublicationRecovery(enricher, event -> { }, corruptStore, recoveryProperties).recover();
            Document permanent = publications.find(eq("rawCommitment", SourceEventCommitment.raw(permanentRaw))).first();
            assertThat(permanent).containsEntry("historyState", "QUARANTINED")
                    .containsEntry("historyFailureCode", "HISTORY_DATA_INVALID")
                    .containsEntry("historyAttempts", 1).containsEntry("attempts", 0);

            long clockOffsetSeconds = 1;
            for (int expectedAttempt = 2; expectedAttempt <= 8; expectedAttempt++) {
                var worker = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(clockOffsetSeconds)), verifier);
                new SourceEnrichedPublicationRecovery(worker, event -> { }, unavailableStore, recoveryProperties).recover();
                Document retried = publications.find(eq("_id", failedId)).first();
                assertThat(retried.getInteger("historyAttempts")).isEqualTo(expectedAttempt);
                assertThat(retried.getInteger("attempts")).isZero();
                if (expectedAttempt < 8) {
                    assertThat(retried.getString("historyState")).isEqualTo("PENDING");
                    assertThat(worker.claimNextEnrichedPublications(1)).isEmpty();
                    clockOffsetSeconds += 1L << (expectedAttempt - 1);
                } else {
                    assertThat(retried).containsEntry("historyState", "QUARANTINED")
                            .containsEntry("historyFailureCode", "HISTORY_RETRY_EXHAUSTED");
                }
            }
            var terminalWorker = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(clockOffsetSeconds + 1)), verifier);
            assertThat(terminalWorker.claimNextEnrichedPublications(1)).isEmpty();
            assertThat(terminalWorker.enrichedPublicationHealth().quarantined()).isEqualTo(2);
            var identities = ownerClient.getDatabase(db).getCollection("source_enriched_business_identities");
            long identityFencesBeforeRedrive = identities.countDocuments(eq("rawCommitment", SourceEventCommitment.raw(failedRaw)));
            assertThat(identityFencesBeforeRedrive).isEqualTo(1);

            var redrive = terminalWorker.redriveEnrichedPublication(failedId, "OPERATOR_RECONCILIATION");
            assertThat(redrive.status()).isEqualTo(SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED);
            assertThat(redrive.recoveryEpoch()).isEqualTo(1);
            Document requeued = publications.find(eq("_id", failedId)).first();
            assertThat(requeued).containsEntry("historyState", "PENDING").containsEntry("historyAttempts", 0)
                    .containsEntry("state", "PENDING").containsEntry("attempts", 0)
                    .containsEntry("redriveReason", "OPERATOR_RECONCILIATION");
            assertThat(new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY))
                    .decrypt(requeued)).isEqualTo(failedPrepared.event());
            assertThat(identities.countDocuments(eq("rawCommitment", SourceEventCommitment.raw(failedRaw))))
                    .isEqualTo(identityFencesBeforeRedrive);
            assertThat(publications.find(eq("_id", failedId)).first().getString("enrichedCommitment"))
                    .isEqualTo(SourceEventCommitment.enriched(failedPrepared.event()));
        } finally {
            unavailableFactory.destroy();
        }
    }

    @Test void publishedHistoryFailureUsesItsOwnRetryScheduleWithoutChangingKafkaAttempts() throws Exception {
        var raw = admit(payload("published-history-retry", TestSourceIssuer.NOW),
                Map.of("sourceEventId", "published-history-retry"));
        enricher.prepareEnrichedPublication(raw, enrichedCandidate(raw));
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        String id = publications
                .find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first().getString("_id");
        publications.updateOne(eq("_id", id), new Document("$set", new Document("state", "PUBLISHED")
                .append("publishedAt", Date.from(TestSourceIssuer.NOW)).append("attempts", 1)
                .append("historyNextAttemptAt", Date.from(TestSourceIssuer.NOW))));
        int kafkaAttemptsBefore = ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .find(eq("_id", id)).first().getInteger("attempts");

        var unavailableFactory = new LettuceConnectionFactory("127.0.0.1", 1);
        unavailableFactory.afterPropertiesSet();
        var template = new StringRedisTemplate(unavailableFactory);
        template.afterPropertiesSet();
        try {
            var unavailableStore = new RedisFeatureStore(template,
                    new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8),
                            Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
            var worker = newEnricher(CLOCK, verifier);
            new SourceEnrichedPublicationRecovery(worker, event -> { }, unavailableStore,
                    new SourceEnrichedPublicationRecoveryProperties(true, 1, 0, 30_000, 5_000)).recover();
            Document failed = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find(eq("_id", id)).first();
            assertThat(failed).containsEntry("state", "PUBLISHED").containsEntry("historyState", "PENDING")
                    .containsEntry("historyAttempts", 1).containsEntry("attempts", kafkaAttemptsBefore);
            assertThat(failed.getDate("historyNextAttemptAt").toInstant()).isEqualTo(TestSourceIssuer.NOW.plusSeconds(1));
            assertThat(worker.claimNextEnrichedPublications(1)).isEmpty();
        } finally {
            unavailableFactory.destroy();
        }
    }

    @Test void appliedHistoryVerificationFailureBacksOffAndQuarantinesWithoutStarvingRecovery() throws Exception {
        var failedRaw = admit(payload("applied-history-retry", TestSourceIssuer.NOW),
                Map.of("sourceEventId", "applied-history-retry"));
        var failedPrepared = enricher.prepareEnrichedPublication(failedRaw, enrichedCandidate(failedRaw));
        var initialClaim = enricher.claimEnrichedPublication(failedRaw, failedPrepared.event());
        assertThat(initialClaim.status()).isEqualTo(SourceEnrichedPublicationClaim.Status.CLAIMED);
        assertThat(enricher.markEnrichedHistoryApplied(initialClaim).state()).isEqualTo(VERIFIED);
        enricher.recordEnrichedPublishFailure(initialClaim);

        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        Document failedBeforeRetry = publications.find(eq("rawCommitment", SourceEventCommitment.raw(failedRaw))).first();
        String failedId = failedBeforeRetry.getString("_id");
        assertThat(failedBeforeRetry).containsEntry("state", "PENDING").containsEntry("historyState", "APPLIED");
        publications.updateOne(eq("_id", failedId), new Document("$set", new Document("nextAttemptAt", Date.from(TestSourceIssuer.NOW))
                .append("historyNextAttemptAt", Date.from(TestSourceIssuer.NOW))));
        int kafkaAttemptsBefore = failedBeforeRetry.getInteger("attempts");

        var unavailableStore = mock(FeatureStore.class);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(unavailableStore).verifyAttestedTransaction(any(), any(), anyString());
        var recoveryProperties = new SourceEnrichedPublicationRecoveryProperties(true, 1, 0, 30_000, 5_000);
        new SourceEnrichedPublicationRecovery(enricher, event -> { }, unavailableStore, recoveryProperties).recover();
        Document failedAfterRetry = publications.find(eq("_id", failedId)).first();
        assertThat(failedAfterRetry).containsEntry("state", "PENDING").containsEntry("historyState", "APPLIED")
                .containsEntry("historyAttempts", 1).containsEntry("attempts", kafkaAttemptsBefore)
                .containsEntry("historyAppliedAt", failedBeforeRetry.get("historyAppliedAt"));
        assertThat(failedAfterRetry.getDate("historyNextAttemptAt").toInstant()).isEqualTo(TestSourceIssuer.NOW.plusSeconds(1));

        var healthyRaw = admit(payload("applied-history-healthy", TestSourceIssuer.NOW.plusSeconds(1),
                "42.00", "merchant-1", "customer-2"),
                Map.of("sourceEventId", "applied-history-healthy"));
        enricher.prepareEnrichedPublication(healthyRaw, enrichedCandidate(healthyRaw));
        var healthyStore = mock(FeatureStore.class);
        doNothing().when(healthyStore).recordAttestedTransaction(any(), any(), anyString());
        new SourceEnrichedPublicationRecovery(enricher, event -> { }, healthyStore, recoveryProperties).recover();
        Document healthy = publications.find(eq("rawCommitment", SourceEventCommitment.raw(healthyRaw))).first();
        assertThat(healthy).containsEntry("state", "PUBLISHED").containsEntry("historyState", "APPLIED");

        var laterWorker = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(1)), verifier);
        var permanentStore = mock(FeatureStore.class);
        doThrow(new com.frauddetection.enricher.exception.FeatureEnrichmentException("FEATURE_HISTORY_REDIS_SCHEMA_CONFLICT"))
                .when(permanentStore).verifyAttestedTransaction(any(), any(), anyString());
        new SourceEnrichedPublicationRecovery(laterWorker, event -> { }, permanentStore, recoveryProperties).recover();
        Document quarantined = publications.find(eq("_id", failedId)).first();
        assertThat(quarantined).containsEntry("state", "PENDING").containsEntry("historyState", "QUARANTINED")
                .containsEntry("historyFailureCode", "HISTORY_DATA_INVALID")
                .containsEntry("historyAppliedAt", failedBeforeRetry.get("historyAppliedAt"));

        var redrive = laterWorker.redriveEnrichedPublication(failedId, "OPERATOR_RECONCILIATION");
        assertThat(redrive.status()).isEqualTo(SourceLineageAccess.EnrichedPublicationRedriveStatus.UNAVAILABLE);
        Document stillQuarantined = publications.find(eq("_id", failedId)).first();
        assertThat(stillQuarantined).containsEntry("historyState", "QUARANTINED")
                .containsEntry("historyAppliedAt", failedBeforeRetry.get("historyAppliedAt"));
        assertThat(new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY))
                .decrypt(stillQuarantined)).isEqualTo(failedPrepared.event());
    }

    private String preparePublishedWithAppliedHistory(TransactionRawEvent raw) {
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        var prepared = enricher.prepareEnrichedPublication(raw, candidate);
        var claim = enricher.claimEnrichedPublication(raw, prepared.event());
        assertThat(claim.status()).isEqualTo(SourceEnrichedPublicationClaim.Status.CLAIMED);
        assertThat(enricher.markEnrichedHistoryApplied(claim).state()).isEqualTo(VERIFIED);
        assertThat(enricher.markEnrichedPublished(claim).state()).isEqualTo(VERIFIED);
        return ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first().getString("_id");
    }

    private String preparePublishedWithPendingHistory(TransactionRawEvent raw) {
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        enricher.prepareEnrichedPublication(raw, candidate);
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        String publicationId = publications.find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first().getString("_id");
        publications.updateOne(eq("_id", publicationId), new Document("$set", new Document("state", "PUBLISHED")
                .append("publishedAt", Date.from(CLOCK.instant())).append("attempts", 1)));
        return publicationId;
    }

    @Test void enrichedPublishExhaustionHasBoundedAuditedRedriveAndRefusesFurtherEpochs() throws Exception {
        var raw = admit(Map.of());
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        var prepared = enricher.prepareEnrichedPublication(raw, candidate);
        var publicationId = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find().first().getString("_id");
        for (int attempt = 0; attempt < 8; attempt++) {
            var worker = newEnricher(Clock.offset(CLOCK, Duration.ofSeconds(600L * attempt)), policy(false));
            var claims = worker.claimNextEnrichedPublications(1);
            assertThat(claims).as("attempt %d publication=%s", attempt,
                    ownerClient.getDatabase(db).getCollection("source_enriched_publications").find(eq("_id", publicationId)).first().toJson())
                    .hasSize(1);
            worker.recordEnrichedPublishFailure(claims.getFirst());
        }
        Document exhausted = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find(eq("_id", publicationId)).first();
        assertThat(exhausted).containsEntry("state", "RETRY_EXHAUSTED").containsEntry("failureCode", "PUBLISH_FAILED")
                .containsEntry("attempts", 8);
        assertThat(enricher.redriveEnrichedPublication(publicationId, "FORCE").status())
                .isEqualTo(SourceLineageAccess.EnrichedPublicationRedriveStatus.INVALID);
        var redrive = enricher.redriveEnrichedPublication(publicationId, "BROKER_RECOVERED");
        assertThat(redrive.status()).isEqualTo(SourceLineageAccess.EnrichedPublicationRedriveStatus.REQUEUED);
        assertThat(redrive.recoveryEpoch()).isEqualTo(1);
        Document queued = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find(eq("_id", publicationId)).first();
        assertThat(queued.getString("state")).isEqualTo("PENDING");
        assertThat(new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY))
                .decrypt(queued)).isEqualTo(prepared.event());
        ownerClient.getDatabase(db).getCollection("source_enriched_publications").updateOne(eq("_id", publicationId),
                set("state", "RETRY_EXHAUSTED"));
        ownerClient.getDatabase(db).getCollection("source_enriched_publications").updateOne(eq("_id", publicationId),
                set("failureCode", "PUBLISH_FAILED"));
        ownerClient.getDatabase(db).getCollection("source_enriched_publications").updateOne(eq("_id", publicationId),
                set("recoveryEpoch", 3));
        ownerClient.getDatabase(db).getCollection("source_enriched_publications").updateOne(eq("_id", publicationId),
                set("attempts", 8));
        assertThat(enricher.redriveEnrichedPublication(publicationId, "BROKER_RECOVERED").status())
                .isEqualTo(SourceLineageAccess.EnrichedPublicationRedriveStatus.EXHAUSTED);
    }

    @Test void enrichedRecoveryQuarantinesTamperedCiphertextAndCurrentlyRevokedAuthority() throws Exception {
        var raw = admit(payload("tampered-enriched-recovery-tx", TestSourceIssuer.NOW),
                Map.of("sourceEventId", "tampered-enriched-recovery"));
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        var prepared = enricher.prepareEnrichedPublication(raw, candidate);

        var revokedRaw = admit(payload("revoked-enriched-recovery-tx", TestSourceIssuer.NOW.plus(Duration.ofMinutes(1)),
                "42.00", "merchant-1", "customer-2"),
                Map.of("sourceEventId", "revoked-enriched-recovery"));
        var revokedCandidate = new TransactionEnrichedEventMapper().toAttestedEvent(revokedRaw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(revokedRaw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        var revokedPrepared = enricher.prepareEnrichedPublication(revokedRaw, revokedCandidate);

        assertThat(raw.transactionId()).isNotEqualTo(revokedRaw.transactionId());
        assertThat(prepared.authority().state()).isEqualTo(VERIFIED);
        assertThat(revokedPrepared.authority().state()).isEqualTo(VERIFIED);
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        Document tamperedRow = publications.find(eq("rawCommitment", SourceEventCommitment.raw(raw))).first();
        Document revokedRow = publications.find(eq("rawCommitment", SourceEventCommitment.raw(revokedRaw))).first();
        assertThat(tamperedRow).isNotNull();
        assertThat(revokedRow).isNotNull();
        assertThat(publications.countDocuments()).isEqualTo(2L);

        // Both rows share the test clock instant; order the tampered row first for deterministic recovery selection.
        publications.updateOne(eq("_id", tamperedRow.getString("_id")),
                set("preparedAt", Date.from(CLOCK.instant().minusSeconds(1))));
        byte[] ciphertext = tamperedRow.get("ciphertext", org.bson.types.Binary.class).getData().clone();
        ciphertext[ciphertext.length - 1] ^= 1;
        publications.updateOne(eq("_id", tamperedRow.getString("_id")),
                set("ciphertext", new org.bson.types.Binary(ciphertext)));
        assertThat(enricher.claimNextEnrichedPublications(1)).isEmpty();
        tamperedRow = publications.find(eq("_id", tamperedRow.getString("_id"))).first();
        assertThat(tamperedRow).containsEntry("state", "QUARANTINED").containsEntry("failureCode", "PAYLOAD_UNAVAILABLE");

        var revokedWorker = newEnricher(CLOCK, new SourceSignatureVerifier(
                new SourceVerificationProperties(List.of(TestSourceIssuer.policy(0, true))), CLOCK));
        assertThat(revokedWorker.claimNextEnrichedPublications(2)).isEmpty();
        revokedRow = publications.find(eq("rawCommitment", SourceEventCommitment.raw(revokedRaw))).first();
        assertThat(revokedRow).containsEntry("state", "QUARANTINED").containsEntry("failureCode", "AUTHORITY_DENIED");
        assertThat(SourceEventCommitment.enriched(prepared.event())).isNotEqualTo(SourceEventCommitment.enriched(revokedCandidate));
    }

    private MongoSourceLineage newEnricher(Clock clock, SourceSignatureVerifier verifier) {
        return new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, clock,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
    }

    @Test void exactReplayIsIdempotentButDistinctObservationWithSameBusinessIdFailsClosed() throws Exception {
        var first = admit(Map.of());
        enrich(first);
        var enriched = lastEnriched();
        assertThat(enricher.bindEnriched(first, enriched).state()).isEqualTo(VERIFIED);
        assertThat(scorer.enriched(roundTrip(enriched, TransactionEnrichedEvent.class)).source().sourceEventId()).isEqualTo("source-event-42");
        var second = admit(Map.of("sourceEventId", "another-source-event"));
        assertThat(enricher.raw(second).source().sourceEventId()).isEqualTo("another-source-event");
        assertThat(enricher.raw(second).receiptId()).isNotEqualTo(enricher.raw(first).receiptId());
        assertThatThrownBy(() -> enrich(second)).hasMessage("FEATURE_BUSINESS_TRANSACTION_ID_CONFLICT");
        assertThat(enrichedWire).hasSize(1);
        assertThat(scorer.enriched(enriched).source().sourceEventId()).isEqualTo("source-event-42");
        assertThat(enricherClient.getDatabase(db).getCollection("source_lineage_enriched").countDocuments()).isEqualTo(1);
    }

    @Test void repeatedSignedSourceReusesTheSameReceiverRawFactWithoutSpendingLineageLinks() throws Exception {
        byte[] signedPayload = payload();
        String signedProof = encodedProof(signedPayload, Map.of());
        var first = service(publisher()).ingest(signedProof, signedPayload);
        TransactionRawEvent canonical = lastRaw();
        for (int retry = 0; retry < 35; retry++) {
            var response = service(publisher()).ingest(signedProof, signedPayload);
            assertThat(response.eventId()).isEqualTo(canonical.eventId());
            assertThat(response.correlationId()).isEqualTo(canonical.correlationId());
        }
        assertThat(rawWire).hasSize(1);
        assertThat(enricher.raw(canonical).receiptId()).isEqualTo(owner.raw(canonical).receiptId());
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw")
                .countDocuments(eq("receiptId", owner.raw(canonical).receiptId()))).isEqualTo(1);
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isEqualTo(1);
    }

    @Test void concurrentIdenticalSignedRequestsCommitOneReceiptRawLinkAndPublicationIntent() throws Exception {
        byte[] signedPayload = payload();
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var evidence = verified(signedPayload, Map.of());
        var mapper = new TransactionRequestMapper();
        var firstCandidate = mapper.toEvent(request, "correlation-a");
        var secondCandidate = mapper.toEvent(request, "correlation-b");
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return receipts.acceptRaw(evidence, firstCandidate); });
            var b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return receipts.acceptRaw(evidence, secondCandidate); });
            var first = a.get(20, TimeUnit.SECONDS);
            var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.status()).isIn(SourceReceiptStore.Status.ADMITTED, SourceReceiptStore.Status.EXISTING);
            assertThat(second.status()).isIn(SourceReceiptStore.Status.ADMITTED, SourceReceiptStore.Status.EXISTING);
            assertThat(first.receipt().id()).isEqualTo(second.receipt().id());
            assertThat(first.event()).isEqualTo(second.event());
            assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw")
                    .countDocuments(eq("receiptId", first.receipt().id()))).isEqualTo(1);
            assertThat(ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                    .countDocuments(eq("_id", first.receipt().id()))).isEqualTo(1);
        }
    }

    @Test void rawLineageBindingRequiresTheReceiptOwnersActiveTransaction() throws Exception {
        byte[] signedPayload = payload();
        var evidence = verified(signedPayload, Map.of());
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var candidate = new TransactionRequestMapper().toEvent(request, "uncommitted-correlation");
        String receiptId = SourceReceiptCommitment.identity(evidence.scope());

        try (ClientSession session = ownerClient.startSession()) {
            assertThat(owner.bindRawWithinTransaction(session, evidence, receiptId, candidate).state()).isEqualTo(UNAVAILABLE);
        }

        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isZero();
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw").countDocuments()).isZero();
    }

    @Test void unknownMongoCommitNeverReportsAcceptanceAndRetryReusesCommittedRawIntent() throws Exception {
        var commitAttempts = new java.util.concurrent.atomic.AtomicInteger();
        MongoClient faultClient = commitFault(ownerClient, (session, method, args) -> {
            Object result = invoke(session, method, args);
            if (method.getName().equals("commitTransaction")) {
                commitAttempts.incrementAndGet();
                throw unknownCommit();
            }
            return result;
        });
        var faultOwner = new MongoSourceLineage(faultClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        var faultStore = new MongoSourceReceiptStore(faultClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, faultOwner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        byte[] signedPayload = payload();
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var evidence = verified(signedPayload, Map.of());
        var candidate = new TransactionRequestMapper().toEvent(request, "unknown-commit-correlation");

        var uncertain = faultStore.acceptRaw(evidence, candidate);
        assertThat(uncertain.status()).isEqualTo(SourceReceiptStore.Status.UNRESOLVED);
        assertThat(commitAttempts.get()).isEqualTo(MongoSourceReceiptStore.ATTEMPTS);
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw").countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION).countDocuments()).isEqualTo(1);

        var retried = receipts.acceptRaw(evidence, candidate);
        assertThat(retried.status()).isEqualTo(SourceReceiptStore.Status.EXISTING);
        assertThat(retried.event()).isEqualTo(candidate);
    }

    @Test void recoveryLeaseAllowsOnlyOneConcurrentPublisherToClaimTheRawIntent() throws Exception {
        byte[] signedPayload = payload();
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var accepted = receipts.acceptRaw(verified(signedPayload, Map.of()),
                new TransactionRequestMapper().toEvent(request, "lease-correlation"));
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> receipts.recoverRawPublications(event -> {
                sends.incrementAndGet();
                publishing.countDown();
                awaitLatch(release);
            }, 1));
            assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> receipts.recoverRawPublications(event -> sends.incrementAndGet(), 1));
            assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(sends.get()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", accepted.receipt().id())).first().getString("state")).isEqualTo("PUBLISHED");
    }

    @Test void changedSignedPayloadUnderOneSourceIdentityFencesTheReceipt() throws Exception {
        var first = admit(Map.of());
        String receiptId = owner.raw(first).receiptId();
        byte[] changedPayload = new String(payload(), StandardCharsets.UTF_8)
                .replace("\"transactionId\":\"transaction-1\"", "\"transactionId\":\"transaction-changed\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service(publisher()).ingest(encodedProof(changedPayload, Map.of()), changedPayload))
                .isInstanceOf(SourceIngestException.class).hasMessage("SOURCE_RECEIPT_CONFLICT");
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_identities")
                .find(eq("_id", receiptId)).first().getString("state")).isEqualTo("CONFLICT");
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw")
                .countDocuments(eq("receiptId", receiptId))).isEqualTo(1);
    }

    @Test void pendingRawIntentIsQuarantinedWhenTheCurrentSourcePolicyRevokesIt() throws Exception {
        byte[] signedPayload = payload();
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var candidate = new TransactionRequestMapper().toEvent(request, "revocation-correlation");
        var accepted = receipts.acceptRaw(verified(signedPayload, Map.of()), candidate);
        assertThat(accepted.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);

        var revokedPolicy = policy(true);
        var revokedOwner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, revokedPolicy, CLOCK);
        var revokedStore = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), revokedPolicy, revokedOwner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        AtomicReference<TransactionRawEvent> published = new AtomicReference<>();
        assertThat(revokedStore.recoverRawPublications(published::set, 1)).isEqualTo(1);
        assertThat(published.get()).isNull();
        Document outbox = ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .find(eq("_id", accepted.receipt().id())).first();
        assertThat(outbox.getString("state")).isEqualTo("QUARANTINED");
        assertThat(outbox.getString("failureCode")).isEqualTo("AUTHORITY_DENIED");
    }

    @Test void expiryDeletesEncryptedReplayPayloadButKeepsThePermanentSourceIdentityFence() throws Exception {
        byte[] signedPayload = payload();
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var accepted = receipts.acceptRaw(verified(signedPayload, Map.of()),
                new TransactionRequestMapper().toEvent(request, "expiry-correlation"));
        assertThat(accepted.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);
        attestedRawEventIds.add(accepted.event().eventId());
        enrich(accepted.event());
        assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_business_identities")
                .countDocuments(eq("receiptId", accepted.receipt().id()))).isEqualTo(1L);
        assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .countDocuments(eq("receiptId", accepted.receipt().id()))).isEqualTo(1);
        ownerClient.getDatabase(db).getCollection("source_admission_identities").updateOne(
                eq("_id", accepted.receipt().id()), com.mongodb.client.model.Updates.set("expiresAt", Date.from(CLOCK.instant().minusSeconds(1))));

        assertThat(receipts.compactExpired(1)).isEqualTo(1);
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_identities")
                .find(eq("_id", accepted.receipt().id())).first().getString("state")).isEqualTo("TOMBSTONED");
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts")
                .countDocuments(eq("_id", accepted.receipt().id()))).isZero();
        assertThat(ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .countDocuments(eq("_id", accepted.receipt().id()))).isZero();
        assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .countDocuments(eq("receiptId", accepted.receipt().id()))).isZero();
        assertThat(ownerClient.getDatabase(db).getCollection("source_enriched_business_identities")
                .countDocuments(eq("receiptId", accepted.receipt().id()))).isZero();
        assertThat(receipts.acceptRaw(verified(signedPayload, Map.of()), accepted.event()).status())
                .isEqualTo(SourceReceiptStore.Status.TOMBSTONED);
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_identities")
                .countDocuments(eq("_id", accepted.receipt().id()))).isEqualTo(1);
    }

    @Test void receiptCapacityBoundsDurableRawPublicationQueue() throws Exception {
        String capacityDb = "rawcap_" + UUID.randomUUID().toString().replace("-", "");
        var limits = new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 1);
        new MongoSourceReceiptStore(ownerClient, capacityDb, CLOCK, limits, verifier);
        var capacityOwner = new MongoSourceLineage(ownerClient, capacityDb, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        var bounded = new MongoSourceReceiptStore(ownerClient, capacityDb, CLOCK, limits, verifier, capacityOwner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        byte[] signedPayload = payload();
        var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
        var first = bounded.acceptRaw(verified(signedPayload, Map.of()),
                new TransactionRequestMapper().toEvent(request, "capacity-a"));
        var second = bounded.acceptRaw(verified(signedPayload, Map.of("sourceEventId", "source-event-capacity-2")),
                new TransactionRequestMapper().toEvent(request, "capacity-b"));

        assertThat(first.status()).isEqualTo(SourceReceiptStore.Status.ADMITTED);
        assertThat(second.status()).isEqualTo(SourceReceiptStore.Status.CAPACITY_EXCEEDED);
        assertThat(ownerClient.getDatabase(capacityDb).getCollection(MongoSourceRawPublicationSchema.COLLECTION).countDocuments()).isEqualTo(1);
        assertThat(ownerClient.getDatabase(capacityDb).getCollection("source_lineage_raw").countDocuments()).isEqualTo(1);
    }

    @Test void restartedPublisherReplaysEncryptedRawIntentToKafkaWithAtLeastOnceExactIdentity() throws Exception {
        var kafka = FraudPlatformContainers.kafka();
        if (!kafka.isRunning()) { kafka.start(); }
        String topic = "raw-recovery-" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonKafkaSerializer.class.getName());
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "raw-recovery-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (var producer = new KafkaProducer<String, TransactionRawEvent>(producerProperties);
             var consumer = new KafkaConsumer<String, String>(consumerProperties)) {
            consumer.subscribe(List.of(topic));
            consumer.poll(Duration.ofSeconds(1));
            byte[] signedPayload = payload();
            var request = JSON.readValue(signedPayload, IngestTransactionRequest.class);
            var candidate = new TransactionRequestMapper().toEvent(request, "raw-recovery-correlation");
            String signedProof = encodedProof(signedPayload, Map.of());
            String javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
            String childClasspath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Process child = new ProcessBuilder(javaCommand, "-cp", childClasspath,
                    SourceRawPublicationCrashWorker.class.getName(), db, MONGO.ownerUri(db), signedProof,
                    Base64.getEncoder().encodeToString(signedPayload))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("RAW crash worker did not stop after durable acceptance");
            }
            assertThat(child.exitValue()).isZero();
            var evidence = verified(signedPayload, Map.of());
            String receiptId = SourceReceiptCommitment.identity(evidence.scope());
            Document identity = ownerClient.getDatabase(db).getCollection("source_admission_identities").find(eq("_id", receiptId)).first();
            Document receiptDocument = ownerClient.getDatabase(db).getCollection("source_admission_receipts").find(eq("_id", receiptId)).first();
            assertThat(identity).isNotNull();
            assertThat(receiptDocument).isNotNull();
            String expectedBinding = SourceReceiptCommitment.binding(evidence.scope(), evidence.keyCommitment());
            assertThat(identity.getString("bindingCommitment")).isEqualTo(expectedBinding);
            assertThat(receiptDocument.getString("bindingCommitment")).isEqualTo(expectedBinding);
            assertThat(receiptDocument.getString("policyVersion")).isEqualTo(evidence.policyVersion());
            assertThat(receiptDocument.getString("verifiedAt")).isEqualTo(evidence.verifiedAt().toString());
            assertThat(identity.getString("state")).isEqualTo("ADMITTED");
            assertThat(identity.getInteger("schemaVersion")).isEqualTo(1);
            assertThat(receiptDocument.getInteger("schemaVersion")).isEqualTo(1);
            assertThat(receiptDocument.getString("keyCommitment")).isEqualTo(evidence.keyCommitment());
            assertThat(identity.get("admittedAt")).isEqualTo(receiptDocument.get("admittedAt"));
            assertThat(identity.get("expiresAt")).isEqualTo(receiptDocument.get("expiresAt"));
            assertThat(SourceReceiptCommitment.identity(evidence.scope())).isEqualTo(identity.getString("_id"));
            assertThat(verifier.receiptPolicyDenial(evidence.scope(), evidence.keyCommitment(), evidence.policyVersion(),
                    evidence.verifiedAt())).isNull();
            var replayAdmission = receipts.admit(evidence);
            assertThat(replayAdmission.status()).isEqualTo(SourceReceiptStore.Status.EXISTING);
            Document durableIntent = ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION).find().first();
            assertThat(durableIntent.getString("state")).isEqualTo("PENDING");
            assertThat(durableIntent.getDate("expiresAt").toInstant()).isEqualTo(replayAdmission.receipt().expiresAt());
            assertThat(durableIntent.getString("_id")).isEqualTo(durableIntent.getString("receiptId"));
            byte[] encryptedPayload = new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)).decrypt(durableIntent);
            TransactionRawEvent durableEvent = JSON.readValue(new String(encryptedPayload, StandardCharsets.UTF_8), TransactionRawEvent.class);
            assertThat(SourceEventCommitment.raw(durableEvent)).isEqualTo(durableIntent.getString("rawCommitment"));
            var admitted = receipts.acceptRaw(evidence, candidate);
            assertThat(admitted.status()).isEqualTo(SourceReceiptStore.Status.EXISTING);
            assertThat(ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                    .find(eq("_id", admitted.receipt().id())).first().toJson()).doesNotContain("customer-1", "203.0.113.24");

            byte[] rotatedKeyBytes = new byte[32];
            Arrays.fill(rotatedKeyBytes, (byte) 1);
            String rotatedKey = Base64.getEncoder().encodeToString(rotatedKeyBytes);
            var rotatedProperties = new SourceRawPublicationProperties(true, "raw-key-2",
                    Map.of("raw-key-1", RAW_KEY, "raw-key-2", rotatedKey), Duration.ofSeconds(1), Duration.ofSeconds(30), 10, 8);
            var restarted = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                    new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, owner,
                    new SourceRawPublicationCipher("raw-key-2", rotatedProperties.encryptionKeys()), rotatedProperties);
            assertThat(restarted.recoverRawPublications(event -> {
                sendToKafka(producer, topic, event);
                throw new IllegalStateException("broker confirmation lost after publication");
            }, 1)).isEqualTo(1);
            ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                    .updateOne(eq("_id", admitted.receipt().id()), com.mongodb.client.model.Updates.set("nextAttemptAt", Date.from(CLOCK.instant())));
            assertThat(restarted.recoverRawPublications(event -> sendToKafka(producer, topic, event), 1)).isEqualTo(1);

            List<TransactionRawEvent> deliveries = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (deliveries.size() < 2 && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(record -> deliveries.add(JSON.readValue(record.value(), TransactionRawEvent.class)));
            }
            assertThat(deliveries).hasSize(2).allSatisfy(event -> assertThat(event).isEqualTo(admitted.event()));
            enrich(deliveries.getFirst());
            enrich(deliveries.getLast());
            assertThat(enrichedWire).hasSize(1);
            assertThat(ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                    .find(eq("_id", admitted.receipt().id())).first().getString("state")).isEqualTo("PUBLISHED");
        }
    }

    @Test void redisFeatureHistoryIsIsolatedByVerifiedInstitutionAndSourceEpoch() throws Exception {
        var a = TestSourceIssuer.policy(0, false);
        var b = new SourceVerificationProperties.TrustedKey("second-test-issuer", "institution-b", a.source(), a.sourceEpoch(),
                a.signingKeyVersion(), a.algorithm(), a.publicKeyBase64(), a.notBefore(), a.notAfter(), false, a.purpose(), a.audience(), a.policyVersion());
        var nextEpoch = new SourceVerificationProperties.TrustedKey(a.issuer(), a.institution(), a.source(), "epoch-b",
                a.signingKeyVersion(), a.algorithm(), a.publicKeyBase64(), a.notBefore(), a.notAfter(), false,
                a.purpose(), a.audience(), a.policyVersion());
        verifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(a, b, nextEpoch)), CLOCK);
        owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier, owner,
                new SourceRawPublicationCipher("raw-key-1", Map.of("raw-key-1", RAW_KEY)), RAW_PUBLICATION_PROPERTIES);
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
        var first = admit(Map.of());
        var second = admit(Map.of("issuer", b.issuer(), "institution", b.institution()));
        var nextEpochEvent = admit(Map.of("sourceEpoch", "epoch-b"));
        assertThat(List.of(first, second, nextEpochEvent)).extracting(TransactionRawEvent::transactionId).containsOnly(first.transactionId());
        assertThat(List.of(first, second, nextEpochEvent).stream().map(enricher::raw).toList())
                .allSatisfy(lineage -> assertThat(lineage.state()).isEqualTo(VERIFIED));

        var redis = FraudPlatformContainers.redis();
        if (!redis.isRunning()) { redis.start(); }
        var connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        try {
            var store = new RedisFeatureStore(redisTemplate, new com.frauddetection.enricher.config.FeatureStoreProperties(
                    Duration.ofMinutes(1), Duration.ofDays(7), Duration.ofDays(8), Duration.ofDays(180), Duration.ofDays(30)),
                    new CurrencyAmountConverter());
            var service = new TransactionFeatureEnricherService(store, new TransactionFeatureCalculator(new CurrencyAmountConverter()),
                    new TransactionEnrichedEventMapper(), event -> enrichedWire.add(serialize(event)), enricher);
            var firstNamespace = FeatureNamespace.attested(enricher.raw(first));
            var secondNamespace = FeatureNamespace.attested(enricher.raw(second));
            var epochNamespace = FeatureNamespace.attested(enricher.raw(nextEpochEvent));
            assertThat(firstNamespace).isNotEqualTo(secondNamespace);
            assertThat(firstNamespace).isNotEqualTo(epochNamespace);
            assertThat(secondNamespace).isNotEqualTo(epochNamespace);
            assertThat(firstNamespace.toString()).doesNotContain("institution-a", TestSourceIssuer.ISSUER);

            service.enrich(first);
            service.enrich(second);
            service.enrich(nextEpochEvent);
            service.enrich(first); // An exact retry stays idempotent inside its source namespace.

            assertThat(enrichedWire).hasSize(3);
            List<Object> recentTransactionCounts = new ArrayList<>();
            for (byte[] bytes : enrichedWire) {
                recentTransactionCounts.add(JSON.readValue(bytes, TransactionEnrichedEvent.class)
                        .featureSnapshot().get("recentTransactionCount"));
            }
            assertThat(recentTransactionCounts).containsOnly(1);
            for (var pair : List.of(Map.entry(first, firstNamespace), Map.entry(second, secondNamespace),
                    Map.entry(nextEpochEvent, epochNamespace))) {
                assertThat(store.hasRecordedTransaction(pair.getKey(), pair.getValue())).isTrue();
                assertThat(store.loadSnapshot(pair.getKey(), pair.getValue()).recentTransactionCount()).isEqualTo(1);
            }

            assertThat(store.hasRecordedTransaction(first, FeatureNamespace.legacy())).isFalse();
            store.recordTransaction(first); // Legacy keys retain their original names and behavior.
            assertThat(store.hasRecordedTransaction(first)).isTrue();
            assertThat(store.loadSnapshot(first).recentTransactionCount()).isEqualTo(1);
            assertThat(store.loadSnapshot(first, firstNamespace).recentTransactionCount()).isEqualTo(1);

            try (var pool = Executors.newFixedThreadPool(4)) {
                var writes = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 12; index++) {
                    TransactionEnrichedEvent stored = JSON.readValue(enrichedWire.getFirst(), TransactionEnrichedEvent.class);
                    String commitment = SourceEventCommitment.attestedHistory(enricher.raw(first).receiptId(), stored);
                    writes.add(pool.submit(() -> store.recordAttestedTransaction(stored, firstNamespace, commitment)));
                }
                for (var write : writes) { write.get(10, TimeUnit.SECONDS); }
            }
            assertThat(store.loadSnapshot(first, firstNamespace).recentTransactionCount()).isEqualTo(1);
        } finally {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            connectionFactory.destroy();
        }

        var wrongReader = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, policy(false), CLOCK);
        assertThat(wrongReader.raw(second).state()).isEqualTo(REVOKED);
    }

    @Test void lateRejectedObservationRetainsTerminalDispositionAfterAnotherAdmissionReusesGate() throws Exception {
        var redis = FraudPlatformContainers.redis();
        if (!redis.isRunning()) { redis.start(); }
        var connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        try {
            var store = new RedisFeatureStore(redisTemplate, new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7),
                    Duration.ofDays(8), Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
            List<TransactionEnrichedEvent> published = new CopyOnWriteArrayList<>();
            var publisher = new com.frauddetection.enricher.messaging.TransactionEnrichedEventPublisher() {
                @Override public void publish(TransactionEnrichedEvent event) { published.add(event); }
                @Override public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) {
                    published.add(event);
                    enrichedOriginProofs.put(event.eventId(), ORIGIN_PROOFS.signEnriched(event, origin));
                }
            };
            var service = new TransactionFeatureEnricherService(store,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, enricher, ORIGIN_PROOFS);

            TransactionRawEvent first = admit(payload("late-a", TestSourceIssuer.NOW), Map.of("sourceEventId", "late-a-source"));
            service.enrich(first, ORIGIN_PROOFS.signRaw(first, SourceOriginProofs.Assertion.KNOWN_ATTESTED));
            TransactionRawEvent later = admit(payload("late-b", TestSourceIssuer.NOW.plus(Duration.ofMinutes(2))),
                    Map.of("sourceEventId", "late-b-source"));
            service.enrich(later, ORIGIN_PROOFS.signRaw(later, SourceOriginProofs.Assertion.KNOWN_ATTESTED));
            TransactionRawEvent delayed = admit(payload("late-c", TestSourceIssuer.NOW.plusSeconds(30)),
                    Map.of("sourceEventId", "late-c-source"));

            String delayedProof = ORIGIN_PROOFS.signRaw(delayed, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            assertThat(ORIGIN_PROOFS.verifyRaw(delayed, delayedProof).assertion())
                    .isEqualTo(SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            assertThatThrownBy(() -> service.enrich(delayed, delayedProof))
                    .isInstanceOf(com.frauddetection.enricher.exception.OutOfOrderFeatureEventException.class)
                    .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThatThrownBy(() -> service.enrich(delayed, "invalid-origin-proof"))
                    .isInstanceOf(com.frauddetection.enricher.exception.FeatureEnrichmentException.class)
                    .hasMessage("FEATURE_ORIGIN_UNRESOLVED");
            TransactionRawEvent tampered = change(delayed, "transactionId", "tampered-late-c", TransactionRawEvent.class);
            assertThatThrownBy(() -> service.enrich(tampered, delayedProof))
                    .isInstanceOf(com.frauddetection.enricher.exception.FeatureEnrichmentException.class)
                    .hasMessage("FEATURE_ORIGIN_UNRESOLVED");
            var revokedEnricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER,
                    policy(true), CLOCK, new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
            var revokedService = new TransactionFeatureEnricherService(store,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, revokedEnricher, ORIGIN_PROOFS);
            assertThatThrownBy(() -> revokedService.enrich(delayed, delayedProof))
                    .isInstanceOf(com.frauddetection.enricher.exception.FeatureEnrichmentException.class)
                    .hasMessage("FEATURE_ORIGIN_UNRESOLVED");
            var expiredEnricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER,
                    verifier, Clock.offset(CLOCK, Duration.ofHours(3)),
                    new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
            var expiredService = new TransactionFeatureEnricherService(store,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, expiredEnricher, ORIGIN_PROOFS);
            assertThatThrownBy(() -> expiredService.enrich(delayed, delayedProof))
                    .isInstanceOf(com.frauddetection.enricher.exception.FeatureEnrichmentException.class)
                    .hasMessage("FEATURE_ORIGIN_UNRESOLVED");

            assertThat(published).extracting(TransactionEnrichedEvent::transactionId)
                    .containsExactly(first.transactionId(), later.transactionId());
            assertThat(enricher.raw(delayed).state()).isEqualTo(VERIFIED);
            assertThat(enricher.findEnrichedPublication(delayed).publication()).isNull();
            assertThat(enricherClient.getDatabase(db).getCollection("source_lineage_enriched").countDocuments()).isEqualTo(2L);
            assertThat(enricherClient.getDatabase(db).getCollection("source_enriched_publications").countDocuments()).isEqualTo(2L);
            Document initiallyRejectedGate = ownerClient.getDatabase(db).getCollection("source_feature_order_gates")
                    .find(eq("receiptId", enricher.raw(delayed).receiptId())).first();
            assertThat(initiallyRejectedGate).containsEntry("status", "LATE_REJECTED");
            ownerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions").drop();
            SourceRawTerminalDispositionMigration.migrate(ownerClient.getDatabase(db), CLOCK);
            SourceRawTerminalDispositionMigration.migrate(ownerClient.getDatabase(db), CLOCK);
            var dispositionCollection = ownerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions");
            Document migratedDisposition = dispositionCollection.find(eq("receiptId", enricher.raw(delayed).receiptId())).first();
            assertThat(migratedDisposition).containsEntry("receiptId", enricher.raw(delayed).receiptId())
                    .containsEntry("rawCommitment", SourceEventCommitment.raw(delayed))
                    .containsEntry("outcome", "FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThat(dispositionCollection.countDocuments(eq("recordType", "DISPOSITION"))).isEqualTo(1L);
            var dispositionIndexes = dispositionCollection.listIndexes().into(new ArrayList<>());
            assertThat(dispositionIndexes).hasSize(1);
            assertThat(dispositionIndexes.getFirst()).containsEntry("name", "_id_").doesNotContainKey("expireAfterSeconds");
            TransactionRawEvent next = admit(payload("late-d", TestSourceIssuer.NOW.plus(Duration.ofMinutes(4))),
                    Map.of("sourceEventId", "late-d-source"));
            String nextProof = ORIGIN_PROOFS.signRaw(next, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            MongoClient restartedEnricherClient = MONGO.reconnectEnricher(db);
            var restartedEnricher = new MongoSourceLineage(restartedEnricherClient, db, "enricher",
                    MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK,
                    new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
            var restartedService = new TransactionFeatureEnricherService(store,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, restartedEnricher, ORIGIN_PROOFS);
            java.util.concurrent.CountDownLatch concurrentStart = new java.util.concurrent.CountDownLatch(1);
            Throwable[] replayFailure = new Throwable[1];
            try (var pool = Executors.newFixedThreadPool(2)) {
                var processNext = pool.submit(() -> {
                    awaitLatch(concurrentStart);
                    service.enrich(next, nextProof);
                });
                var replayLate = pool.submit(() -> {
                    awaitLatch(concurrentStart);
                    try { restartedService.enrich(delayed, delayedProof); }
                    catch (RuntimeException expected) { replayFailure[0] = expected; }
                });
                concurrentStart.countDown();
                processNext.get(20, TimeUnit.SECONDS);
                replayLate.get(20, TimeUnit.SECONDS);
            }
            assertThat(replayFailure[0]).isInstanceOf(com.frauddetection.enricher.exception.OutOfOrderFeatureEventException.class)
                    .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThat(published).extracting(TransactionEnrichedEvent::transactionId)
                    .containsExactly(first.transactionId(), later.transactionId(), next.transactionId());
            TransactionEnrichedEvent nextEnriched = published.stream()
                    .filter(event -> event.transactionId().equals(next.transactionId())).findFirst().orElseThrow();
            assertThat(score(nextEnriched).event()).isNotNull();
            var delayedAuthority = enricher.raw(delayed);
            var nextAuthority = enricher.raw(next);
            String gateId = SourceFeatureOrderIdentity.identity(delayedAuthority.source(), delayed.customerId());
            Document reusedGate = ownerClient.getDatabase(db).getCollection("source_feature_order_gates")
                    .find(eq("_id", gateId)).first();
            assertThat(reusedGate).containsEntry("status", "RESOLVED")
                    .containsEntry("receiptId", nextAuthority.receiptId())
                    .containsEntry("rawCommitment", nextAuthority.eventCommitment());
            assertThat(enricherClient.getDatabase(db).getCollection("source_lineage_enriched").countDocuments()).isEqualTo(3L);
            assertThat(enricherClient.getDatabase(db).getCollection("source_enriched_publications").countDocuments()).isEqualTo(3L);
            var durableLookup = restartedEnricher.findEnrichedPublication(delayed);
            assertThat(durableLookup.outcome()).isEqualTo(SourceEnrichedPublicationLookup.Outcome.LATE_REJECTED);
            Document durableDisposition = ownerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions")
                    .find(eq("receiptId", delayedAuthority.receiptId())).first();
            assertThat(durableDisposition).containsEntry("receiptId", delayedAuthority.receiptId())
                    .containsEntry("rawCommitment", delayedAuthority.eventCommitment())
                    .containsEntry("outcome", "FEATURE_EVENT_TIME_OUT_OF_ORDER");
            Document conflictingDisposition = new Document(durableDisposition)
                    .append("rawCommitment", "0".repeat(64));
            assertThatThrownBy(() -> enricherClient.getDatabase(db)
                    .getCollection("source_raw_terminal_dispositions").insertOne(conflictingDisposition))
                    .isInstanceOf(com.mongodb.MongoWriteException.class);
            assertThat(ownerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions")
                    .find(eq("receiptId", delayedAuthority.receiptId())).first())
                    .containsEntry("rawCommitment", delayedAuthority.eventCommitment());

            var snapshotAfterNext = store.loadSnapshot(next, FeatureNamespace.attested(nextAuthority));
            assertThatThrownBy(() -> restartedService.enrich(delayed, delayedProof))
                    .isInstanceOf(com.frauddetection.enricher.exception.OutOfOrderFeatureEventException.class)
                    .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThat(store.loadSnapshot(next, FeatureNamespace.attested(nextAuthority))).isEqualTo(snapshotAfterNext);
            assertThat(published).extracting(TransactionEnrichedEvent::transactionId)
                    .containsExactly(first.transactionId(), later.transactionId(), next.transactionId());

            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            assertThatThrownBy(() -> restartedService.enrich(delayed, delayedProof))
                    .isInstanceOf(com.frauddetection.enricher.exception.OutOfOrderFeatureEventException.class)
                    .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThat(published).extracting(TransactionEnrichedEvent::transactionId)
                    .containsExactly(first.transactionId(), later.transactionId(), next.transactionId());

            TransactionRawEvent ackFirst = admit(payload("late-ack-first", TestSourceIssuer.NOW,
                    "10.00", "merchant-late-ack", "customer-late-ack"),
                    Map.of("sourceEventId", "late-ack-first-source"));
            service.enrich(ackFirst, ORIGIN_PROOFS.signRaw(ackFirst, SourceOriginProofs.Assertion.KNOWN_ATTESTED));
            TransactionRawEvent ackLater = admit(payload("late-ack-later", TestSourceIssuer.NOW.plus(Duration.ofMinutes(2)),
                    "20.00", "merchant-late-ack", "customer-late-ack"),
                    Map.of("sourceEventId", "late-ack-later-source"));
            service.enrich(ackLater, ORIGIN_PROOFS.signRaw(ackLater, SourceOriginProofs.Assertion.KNOWN_ATTESTED));
            TransactionRawEvent ackDelayed = admit(payload("late-ack-delayed", TestSourceIssuer.NOW.plusSeconds(30),
                    "30.00", "merchant-late-ack", "customer-late-ack"),
                    Map.of("sourceEventId", "late-ack-delayed-source"));
            var loseNextCommitAck = new AtomicBoolean();
            var lostCommitAcks = new java.util.concurrent.atomic.AtomicInteger();
            MongoClient ackFaultClient = commitFault(enricherClient, (session, method, args) -> {
                Object result = invoke(session, method, args);
                if (method.getName().equals("commitTransaction") && loseNextCommitAck.compareAndSet(true, false)) {
                    lostCommitAcks.incrementAndGet();
                    throw unknownCommit();
                }
                return result;
            });
            var ackFaultLineage = new MongoSourceLineage(ackFaultClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER,
                    verifier, CLOCK, new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
            FeatureStore loseAckOnLateDecision = new FeatureStore() {
                @Override public boolean hasRecordedTransaction(TransactionRawEvent event) {
                    return store.hasRecordedTransaction(event);
                }
                @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) {
                    return store.loadSnapshot(event);
                }
                @Override public void recordTransaction(TransactionRawEvent event) { store.recordTransaction(event); }
                @Override public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                    return store.hasRecordedTransaction(event, namespace);
                }
                @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                    if (event.eventId().equals(ackDelayed.eventId())) {
                        loseNextCommitAck.set(true);
                        throw new com.frauddetection.enricher.exception.OutOfOrderFeatureEventException();
                    }
                    return store.loadSnapshot(event, namespace);
                }
                @Override public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                    store.recordTransaction(event, namespace);
                }
                @Override public void recordAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                                String historyCommitment) {
                    store.recordAttestedTransaction(event, namespace, historyCommitment);
                }
                @Override public void verifyAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                               String historyCommitment) {
                    store.verifyAttestedTransaction(event, namespace, historyCommitment);
                }
            };
            var ackFaultService = new TransactionFeatureEnricherService(loseAckOnLateDecision,
                    new TransactionFeatureCalculator(new CurrencyAmountConverter()), new TransactionEnrichedEventMapper(),
                    publisher, ackFaultLineage, ORIGIN_PROOFS);
            assertThatThrownBy(() -> ackFaultService.enrich(ackDelayed,
                    ORIGIN_PROOFS.signRaw(ackDelayed, SourceOriginProofs.Assertion.KNOWN_ATTESTED)))
                    .isInstanceOf(com.frauddetection.enricher.exception.OutOfOrderFeatureEventException.class)
                    .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThat(lostCommitAcks.get()).isEqualTo(1);
            assertThat(loseNextCommitAck).isFalse();
            assertThat(ackFaultLineage.findEnrichedPublication(ackDelayed).outcome())
                    .isEqualTo(SourceEnrichedPublicationLookup.Outcome.LATE_REJECTED);
            assertThat(ownerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions")
                    .countDocuments(eq("receiptId", enricher.raw(ackDelayed).receiptId()))).isEqualTo(1L);
            assertThatThrownBy(() -> service.enrich(ackDelayed,
                    ORIGIN_PROOFS.signRaw(ackDelayed, SourceOriginProofs.Assertion.KNOWN_ATTESTED)))
                    .isInstanceOf(com.frauddetection.enricher.exception.OutOfOrderFeatureEventException.class)
                    .hasMessage("FEATURE_EVENT_TIME_OUT_OF_ORDER");
            assertThat(published).extracting(TransactionEnrichedEvent::transactionId)
                    .contains(first.transactionId(), later.transactionId(), next.transactionId(), ackFirst.transactionId(),
                            ackLater.transactionId())
                    .doesNotContain(ackDelayed.transactionId());
        } finally {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            connectionFactory.destroy();
        }
    }

    @Test void pendingEarlierRawCannotBeOvertakenBySameCustomerAdmissionOrRecovery() throws Exception {
        var redis = FraudPlatformContainers.redis();
        if (!redis.isRunning()) { redis.start(); }
        var connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        var redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        try {
            var store = new RedisFeatureStore(redisTemplate, new FeatureStoreProperties(Duration.ofMinutes(1), Duration.ofDays(7),
                    Duration.ofDays(8), Duration.ofDays(180), Duration.ofDays(30)), new CurrencyAmountConverter());
            byte[] aPayload = payload("order-a", TestSourceIssuer.NOW);
            byte[] bPayload = payload("order-b", TestSourceIssuer.NOW.plusSeconds(30), "20.00", "merchant-1");
            Map<String, Object> aClaims = Map.of("sourceEventId", "order-a-source");
            Map<String, Object> bClaims = Map.of("sourceEventId", "order-b-source");
            TransactionRawEvent a = admit(aPayload, aClaims);

            AtomicBoolean failBeforeFirstAppend = new AtomicBoolean(true);
            FeatureStore failOnce = new FeatureStore() {
                @Override public boolean hasRecordedTransaction(TransactionRawEvent event) {
                    return store.hasRecordedTransaction(event);
                }
                @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) {
                    return store.loadSnapshot(event);
                }
                @Override public void recordTransaction(TransactionRawEvent event) { store.recordTransaction(event); }
                @Override public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                    return store.hasRecordedTransaction(event, namespace);
                }
                @Override public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                    return store.loadSnapshot(event, namespace);
                }
                @Override public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                    store.recordTransaction(event, namespace);
                }
                @Override public void recordAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                               String historyCommitment) {
                    if (event.transactionId().equals(a.transactionId()) && failBeforeFirstAppend.compareAndSet(true, false)) {
                        throw new IllegalStateException("TEST_TRANSIENT_BEFORE_REDIS_APPEND");
                    }
                    store.recordAttestedTransaction(event, namespace, historyCommitment);
                }
                @Override public void verifyAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                                String historyCommitment) {
                    store.verifyAttestedTransaction(event, namespace, historyCommitment);
                }
            };
            List<TransactionEnrichedEvent> published = new ArrayList<>();
            TransactionEnrichedEventPublisher enrichedPublisher = new TransactionEnrichedEventPublisher() {
                @Override public void publish(TransactionEnrichedEvent event) { published.add(event); }
                @Override public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) {
                    published.add(event);
                    enrichedOriginProofs.put(event.eventId(), ORIGIN_PROOFS.signEnriched(event, origin));
                }
            };
            var calculator = new TransactionFeatureCalculator(new CurrencyAmountConverter());
            var mapper = new TransactionEnrichedEventMapper();
            var firstAttempt = new TransactionFeatureEnricherService(failOnce, calculator, mapper, enrichedPublisher,
                    enricher, ORIGIN_PROOFS);
            String aOriginProof = ORIGIN_PROOFS.signRaw(a, SourceOriginProofs.Assertion.KNOWN_ATTESTED);
            assertThatThrownBy(() -> firstAttempt.enrich(a, aOriginProof))
                    .hasMessage("TEST_TRANSIENT_BEFORE_REDIS_APPEND");

            var database = ownerClient.getDatabase(db);
            // Locate A by its immutable raw parent; the encrypted publication remains PENDING after the failpoint.
            Document pendingA = database.getCollection("source_enriched_publications")
                    .find(eq("rawCommitment", SourceEventCommitment.raw(a))).first();
            assertThat(pendingA).isNotNull().containsEntry("historyState", "PENDING");
            FeatureNamespace namespace = FeatureNamespace.attested(enricher.raw(a));
            assertThat(store.hasRecordedTransaction(a, namespace)).isFalse();
            String aGateId = SourceFeatureOrderIdentity.identity(enricher.raw(a).source(), a.customerId());
            assertThat(SourceFeatureOrderIdentity.identity(verified(bPayload, bClaims).scope(), "customer-1"))
                    .isEqualTo(aGateId);
            assertThat(database.getCollection("source_feature_order_gates").find(eq("_id", aGateId)).first())
                    .containsEntry("status", "ACTIVE").containsEntry("receiptId", enricher.raw(a).receiptId())
                    .containsEntry("rawCommitment", SourceEventCommitment.raw(a));
            int capacityAfterA = database.getCollection("source_admission_limits").find(eq("_id", "capacity"))
                    .first().getInteger("count");

            SourceIngestException blocked = catchThrowableOfType(() -> admit(bPayload, bClaims), SourceIngestException.class);
            assertThat(blocked).as("B admission must wait until A's Redis effect is durably acknowledged").isNotNull()
                    .hasMessage("SOURCE_RECEIPT_ORDER_PENDING");
            assertThat(blocked.status()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);

            Clock recoveryClock = Clock.offset(CLOCK, Duration.ofMinutes(1));
            var recoveryLineage = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER,
                    verifier, recoveryClock,
                    new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
            SourceEnrichedPublicationRecovery recovery = new SourceEnrichedPublicationRecovery(recoveryLineage,
                    enrichedPublisher, store, new SourceEnrichedPublicationRecoveryProperties(true, 10, 0, 1_000));

            assertThat(rawWire).hasSize(1);
            assertThat(database.getCollection("source_admission_limits").find(eq("_id", "capacity"))
                    .first().getInteger("count")).isEqualTo(capacityAfterA);
            assertThat(database.getCollection("source_enriched_publications").countDocuments()).isEqualTo(1L);
            assertThat(published).isEmpty();

            recovery.recover();
            Document appliedA = database.getCollection("source_enriched_publications")
                    .find(eq("rawCommitment", SourceEventCommitment.raw(a))).first();
            assertThat(appliedA).containsEntry("historyState", "APPLIED");
            assertThat(store.hasRecordedTransaction(a, namespace)).isTrue();

            TransactionRawEvent b = admit(bPayload, bClaims);
            TransactionRawEvent admittedB = b;
            new TransactionFeatureEnricherService(store, calculator, mapper, enrichedPublisher, recoveryLineage, ORIGIN_PROOFS)
                    .enrich(admittedB, ORIGIN_PROOFS.signRaw(admittedB, SourceOriginProofs.Assertion.KNOWN_ATTESTED));
            TransactionEnrichedEvent completeB = published.stream()
                    .filter(event -> event.transactionId().equals(admittedB.transactionId())).findFirst().orElseThrow();
            assertThat(completeB.featureSnapshot())
                    .containsEntry(com.frauddetection.common.events.features.FraudFeatureContract.RECENT_TRANSACTION_COUNT, 2)
                    .containsEntry(com.frauddetection.common.events.features.FraudFeatureContract.MERCHANT_FREQUENCY_7D, 2)
                    .containsEntry(com.frauddetection.common.events.features.FraudFeatureContract.RAPID_TRANSFER_TRANSACTION_IDS,
                            List.of(a.transactionId(), admittedB.transactionId()));
            assertThat(completeB.featureSnapshot().get(
                    com.frauddetection.common.events.features.FraudFeatureContract.RECENT_AMOUNT_SUM_PLN))
                    .isEqualTo(new BigDecimal("62.00"));
            assertThat(score(completeB).event()).isNotNull();
        } finally {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            connectionFactory.destroy();
        }
    }

    @Test void corruptedParentMetadataCannotSupplyAuthorityAndStartupNeverRepairsMissingMetadata() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        ownerClient.getDatabase(db).getCollection("source_lineage_raw").updateOne(new Document(), set("eventId", "wrong-parent-id"));
        assertThat(scorer.enriched(enriched).state()).isNotEqualTo(VERIFIED);
        ownerClient.getDatabase(db).getCollection("source_lineage_enriched_counts").drop();
        assertThatThrownBy(() -> new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK))
                .hasMessage("SOURCE_LINEAGE_SCHEMA_INCOMPATIBLE");
        assertThat(ownerClient.getDatabase(db).listCollectionNames().into(new ArrayList<>())).doesNotContain("source_lineage_enriched_counts");
    }

    @Test void missingParentMissingReceiptConflictAndRevokedCurrentKeyCloseExistingBindings() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var revoked = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, policy(true), CLOCK);
        assertThat(revoked.enriched(enriched).state()).isEqualTo(REVOKED);
        var wrongPurpose = new SourceSignatureVerifier(new SourceVerificationProperties(List.of()), CLOCK);
        assertThat(new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, wrongPurpose, CLOCK)
                .enriched(enriched).state()).isEqualTo(REVOKED);
        String receipt = scorer.enriched(enriched).receiptId();
        ownerClient.getDatabase(db).getCollection("source_admission_identities").updateOne(eq("_id", receipt), set("state", "CONFLICT"));
        assertThat(scorer.enriched(enriched).state()).isEqualTo(CONFLICT);
        ownerClient.getDatabase(db).getCollection("source_admission_identities").updateOne(eq("_id", receipt), set("state", "ADMITTED"));
        ownerClient.getDatabase(db).getCollection("source_admission_receipts").deleteOne(eq("_id", receipt));
        assertThat(scorer.enriched(enriched).state()).isEqualTo(UNAVAILABLE);
        ownerClient.getDatabase(db).getCollection("source_lineage_raw").deleteMany(new Document());
        assertThat(scorer.enriched(enriched).state()).isEqualTo(UNAVAILABLE);
    }

    @Test void unauthenticatedWrongPrincipalAndOverprivilegedConsumerAreRejectedByActualDatabase() {
        assertThatThrownBy(() -> new MongoSourceLineage(MONGO.anonymous(), db, "scorer", MongoSourceLineage.Stage.SCORER, verifier, CLOCK))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new MongoSourceLineage(scorerClient, db, "enricher", MongoSourceLineage.Stage.SCORER, verifier, CLOCK))
                .hasMessage("SOURCE_LINEAGE_SERVICE_AUTHENTICATION_REQUIRED");
        assertThatThrownBy(() -> new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.SCORER, verifier, CLOCK))
                .hasMessage("SOURCE_LINEAGE_SERVICE_PRIVILEGES_TOO_BROAD");
        for (String name : List.of("source_admission_receipts", "source_admission_identities", "source_lineage_raw")) {
            assertThatThrownBy(() -> enricherClient.getDatabase(db).getCollection(name).insertOne(new Document("_id", "forged")))
                    .isInstanceOf(MongoCommandException.class).satisfies(error ->
                            assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        }
        assertThatThrownBy(() -> scorerClient.getDatabase(db).getCollection("source_lineage_enriched").deleteMany(new Document()))
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        assertThatThrownBy(() -> enricherClient.getDatabase(db).getCollection("source_feature_order_gates")
                .insertOne(new Document("_id", "a".repeat(64))))
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        assertThatThrownBy(() -> enricherClient.getDatabase(db).getCollection("source_raw_terminal_dispositions")
                .updateOne(new Document(), set("outcome", "ACCEPTED")))
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        assertThatThrownBy(() -> enricherClient.getDatabase(db).getCollection("source_raw_terminal_dispositions")
                .deleteMany(new Document()))
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        assertThatThrownBy(() -> enricherClient.getDatabase(db).runCommand(new Document("collMod", "source_raw_terminal_dispositions")
                .append("validator", new Document())))
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        assertThatThrownBy(() -> scorerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions").find().first())
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
        assertThatThrownBy(() -> scorerClient.getDatabase(db).getCollection("source_raw_terminal_dispositions")
                .insertOne(new Document("_id", "b".repeat(64))))
                .isInstanceOf(MongoCommandException.class).satisfies(error -> assertThat(((MongoCommandException) error).getCode()).isEqualTo(13));
    }

    @Test void wrongStageCannotMintSourceOrDerivedAuthorityAndEnrichmentMustPreserveOriginalInterpretation() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var verified = verified(payload(), Map.of());
        String receipt = scorer.enriched(enriched).receiptId();
        assertThat(scorer.bindRaw(verified, receipt, raw).state()).isNotEqualTo(VERIFIED);
        assertThat(enricher.bindRaw(verified, receipt, raw).state()).isNotEqualTo(VERIFIED);
        assertThat(scorer.bindEnriched(raw, enriched).state()).isNotEqualTo(VERIFIED);
        assertThat(owner.bindRaw(null, receipt, raw).state()).isNotEqualTo(VERIFIED);
        assertThat(enricher.bindEnriched(raw, change(enriched, "customerId", "different", TransactionEnrichedEvent.class)).state()).isEqualTo(CONFLICT);
        ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(enriched));
        ((ObjectNode) node.get("featureSnapshot")).put("sourceVerified", true);
        assertThat(enricher.bindEnriched(raw, JSON.treeToValue(node, TransactionEnrichedEvent.class)).state()).isEqualTo(CONFLICT);
    }

    @Test void verifiedPrivateContextCannotBeReusedForAnotherEventOrUncommittedFeatures() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var lineage = scorer.enriched(enriched);
        var otherEvent = change(enriched, "eventId", "another-event", TransactionEnrichedEvent.class);
        assertThat(FraudScoringRequest.from(otherEvent, lineage).sourceLineage().state()).isEqualTo(CONFLICT);
        var otherFeatures = new LinkedHashMap<>(enriched.featureSnapshot());
        otherFeatures.put("deviceNovelty", false);
        assertThat(new FraudScoringRequest(enriched, otherFeatures, lineage).sourceLineage().state()).isEqualTo(CONFLICT);
        assertThat(new ScoringContext(enriched, otherFeatures, ScoringMode.RULE_BASED, enriched.correlationId(), CLOCK.instant(), lineage)
                .sourceLineage().state()).isEqualTo(CONFLICT);
    }

    @Test void conflictingEnrichedInterpretationCannotReplaceTheCanonicalPublicationAndLinkGrowthIsBounded() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var features = new LinkedHashMap<>(enriched.featureSnapshot());
        features.put("deviceNovelty", false);
        var changed = new TransactionEnrichedEvent(enriched.eventId(), enriched.transactionId(), enriched.correlationId(),
                enriched.customerId(), enriched.accountId(), enriched.createdAt(), enriched.transactionTimestamp(), enriched.transactionAmount(),
                enriched.merchantInfo(), enriched.deviceInfo(), enriched.locationInfo(), enriched.customerContext(), features);
        assertThat(enricher.bindEnriched(raw, changed).state()).isEqualTo(CONFLICT);
        assertThat(scorer.enriched(enriched).state()).isEqualTo(VERIFIED);
        assertThat(enricher.bindEnriched(raw, enriched).state()).isEqualTo(VERIFIED);
        for (int i = 1; i < 32; i++) {
            String customerId = "link-customer-" + i;
            SourceSignatureVerifier.Verified evidence = verified(payload(), Map.of());
            assertThat(evidence.scope()).isEqualTo(enricher.raw(raw).source());
            assertThat(SourceReceiptIdentity.identity(evidence.scope())).isEqualTo(enricher.raw(raw).receiptId());
            assertThat(owner.bindRaw(evidence, enricher.raw(raw).receiptId(), rawLinkForCustomer(raw, "retry-" + i, customerId))
                    .state()).isEqualTo(VERIFIED);
        }
        String receipt = enricher.raw(raw).receiptId();
        String overCapCustomer = "link-customer-32";
        SourceSignatureVerifier.Verified overCapEvidence = verified(payload(), Map.of());
        assertThat(owner.bindRaw(overCapEvidence, receipt, rawLinkForCustomer(raw, "over-cap", overCapCustomer))
                .state()).isEqualTo(UNAVAILABLE);
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw").countDocuments()).isEqualTo(32);
    }

    @Test void unchangedWireContractsReadOldFixturesAndIgnoreSelfAssertedAuthorityWithoutGrantingIt() throws Exception {
        var legacy = new TransactionRequestMapper().toEvent(JSON.readValue(payload(), IngestTransactionRequest.class), "legacy-correlation");
        assertThat(enricher.raw(roundTrip(legacy, TransactionRawEvent.class)).state()).isEqualTo(UNVERIFIED);
        enrich(legacy, SourceLineageAccess.legacyOnly()); var enriched = lastEnriched();
        var forged = change(enriched, "sourceVerified", true, TransactionEnrichedEvent.class);
        assertThat(scorer.enriched(forged).state()).isEqualTo(UNVERIFIED);
        assertThat(JSON.readTree(JSON.writeValueAsBytes(legacy)).propertyNames()).containsExactlyInAnyOrder(
                "eventId", "transactionId", "correlationId", "customerId", "accountId", "paymentInstrumentId", "createdAt",
                "transactionTimestamp", "transactionAmount", "merchantInfo", "deviceInfo", "locationInfo", "customerContext",
                "transactionType", "authorizationMethod", "sourceSystem", "traceId", "attributes");
        assertThat(JSON.readTree(JSON.writeValueAsBytes(enriched)).propertyNames()).containsExactlyInAnyOrder(
                "eventId", "transactionId", "correlationId", "customerId", "accountId", "createdAt", "transactionTimestamp",
                "transactionAmount", "merchantInfo", "deviceInfo", "locationInfo", "customerContext", "featureSnapshot");
        // Before/after producers and consumers share these exact DTO fields and serializer behavior.
        var oldConsumer = JSON.readValue(JSON.writeValueAsBytes(enriched), TransactionEnrichedEvent.class);
        assertThat(oldConsumer).isEqualTo(enriched);
        assertThat(SourceLineageAccess.disabled().enriched(oldConsumer).state()).isEqualTo(UNVERIFIED);
    }

    @Test void mixedDeploymentLosesTrustAtAnOldEnricherOrScorerWithoutBreakingBaselineWireProcessing() throws Exception {
        var raw = admit(Map.of());
        enrich(raw, SourceLineageAccess.legacyOnly()); // previous enricher behavior: no private link writer.
        var oldEnriched = lastEnriched();
        assertThatThrownBy(() -> score(oldEnriched)).isInstanceOf(IllegalStateException.class).hasMessage("SOURCE_ORIGIN_UNRESOLVED");
        recorded.clear();
        enrich(raw);
        var currentEnriched = lastEnriched();
        assertThat(currentEnriched.featureSnapshot()).isEqualTo(oldEnriched.featureSnapshot());
        assertThat(score(currentEnriched).context.sourceLineage().state()).isEqualTo(VERIFIED);
        assertThatThrownBy(() -> score(currentEnriched, SourceLineageAccess.disabled()))
                .isInstanceOf(IllegalStateException.class).hasMessage("SOURCE_ORIGIN_UNRESOLVED");
    }

    @Test void receiptExpiryAndStoreOutageCannotBecomeTrustedWhileOrdinaryScoringStillRuns() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var late = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, verifier,
                Clock.offset(CLOCK, Duration.ofHours(3)));
        assertThat(late.enriched(enriched).state()).isNotEqualTo(VERIFIED);
        scorerClient.close();
        assertThat(scorer.enriched(enriched).state()).isEqualTo(UNAVAILABLE);
        assertThatThrownBy(() -> score(enriched)).isInstanceOf(IllegalStateException.class).hasMessage("SOURCE_ORIGIN_UNRESOLVED");
        var legacyRaw = new TransactionRequestMapper().toEvent(JSON.readValue(payload(), IngestTransactionRequest.class), "legacy-after-outage");
        enrich(legacyRaw);
        var legacyScored = score(lastEnriched());
        assertThat(legacyScored.context.sourceLineage().state()).isEqualTo(UNVERIFIED);
        assertThat(legacyScored.event.eventContractVersion()).isEqualTo(2);
    }

    @Test void validProofWithDisabledSidecarFailsClosedAndLostBrokerAcknowledgementReplaysExactDurableEvent() throws Exception {
        var disabled = new AttestedTransactionIngestService(new TransactionRequestMapper(), verifier, (SourceReceiptStore) null,
                publisher(), new CorrelationIdProvider(), new KafkaTopicProperties("transactions.raw"), validation.getValidator());
        assertThatThrownBy(() -> disabled.ingest(encodedProof(payload(), Map.of()), payload()))
                .isInstanceOf(SourceIngestException.class).hasMessage("SOURCE_LINEAGE_UNAVAILABLE");
        var uncertain = service(event -> { rawWire.add(serialize(event)); throw new IllegalStateException("lost acknowledgement"); });
        var accepted = uncertain.ingest(encodedProof(payload(), Map.of()), payload());
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        TransactionRawEvent first = lastRaw();
        String receiptId = owner.raw(first).receiptId();
        ownerClient.getDatabase(db).getCollection(MongoSourceRawPublicationSchema.COLLECTION)
                .updateOne(eq("_id", receiptId), com.mongodb.client.model.Updates.set("nextAttemptAt", Date.from(CLOCK.instant())));
        assertThat(receipts.recoverRawPublications(publisher(), 1)).isEqualTo(1);
        TransactionRawEvent replay = lastRaw();
        assertThat(replay).isEqualTo(first);
        assertThat(enricher.raw(replay).state()).isEqualTo(VERIFIED);
    }

    private MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new AttestedTransactionIngestController(service(publisher())))
            .setControllerAdvice(new TransactionIngestExceptionHandler()).build(); }
    private AttestedTransactionIngestService service(TransactionRawEventProducer producer) {
        return new AttestedTransactionIngestService(new TransactionRequestMapper(), verifier, receipts, producer,
                new CorrelationIdProvider(), new KafkaTopicProperties("transactions.raw"), validation.getValidator());
    }
    private TransactionRawEventProducer publisher() { return event -> rawWire.add(serialize(event)); }
    private TransactionRawEvent admit(Map<String, Object> claims) throws Exception {
        return admit(payload(), claims);
    }
    private TransactionRawEvent admit(byte[] payload, Map<String, Object> claims) throws Exception {
        service(publisher()).ingest(encodedProof(payload, claims), payload);
        TransactionRawEvent admitted = lastRaw(); attestedRawEventIds.add(admitted.eventId()); return admitted;
    }
    private TransactionRawEvent lastRaw() { return new JacksonKafkaDeserializer<>(TransactionRawEvent.class).deserialize("transactions.raw", rawWire.getLast()); }
    private TransactionEnrichedEvent lastEnriched() { return new JacksonKafkaDeserializer<>(TransactionEnrichedEvent.class).deserialize("transactions.enriched", enrichedWire.getLast()); }
    private void enrich(TransactionRawEvent raw) {
        enrich(raw, enricher);
    }
    private void enrich(TransactionRawEvent raw, SourceLineageAccess access) {
        FeatureStore store = new FeatureStore() {
            public boolean hasRecordedTransaction(TransactionRawEvent event) {
                return hasRecordedTransaction(event, FeatureNamespace.legacy());
            }
            public boolean hasRecordedTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                return recorded.getOrDefault(namespace, Set.of()).contains(event.customerId() + "/" + event.transactionId());
            }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) { return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false); }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event, FeatureNamespace namespace) {
                return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false);
            }
            public void recordTransaction(TransactionRawEvent event) {
                recordTransaction(event, FeatureNamespace.legacy());
            }
            public void recordTransaction(TransactionRawEvent event, FeatureNamespace namespace) {
                recorded.computeIfAbsent(namespace, ignored -> new HashSet<>())
                        .add(event.customerId() + "/" + event.transactionId());
            }
            public void recordAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                   String historyCommitment) {
                recorded.computeIfAbsent(namespace, ignored -> new HashSet<>())
                        .add(event.customerId() + "/" + event.transactionId());
            }
            public void verifyAttestedTransaction(TransactionEnrichedEvent event, FeatureNamespace namespace,
                                                  String historyCommitment) {
                if (!recorded.getOrDefault(namespace, Set.of()).contains(event.customerId() + "/" + event.transactionId())) {
                    throw new IllegalStateException("FEATURE_HISTORY_REBUILD_REQUIRED");
                }
            }
        };
        if (access.isLegacyOnly()) {
            new TransactionFeatureEnricherService(store, new TransactionFeatureCalculator(new CurrencyAmountConverter()),
                    new TransactionEnrichedEventMapper(), event -> enrichedWire.add(serialize(event)), access).enrich(raw);
            return;
        }
        SourceOriginProofs.Assertion assertion = attestedRawEventIds.contains(raw.eventId())
                ? SourceOriginProofs.Assertion.KNOWN_ATTESTED : SourceOriginProofs.Assertion.PROVEN_LEGACY;
        var publisher = new TransactionEnrichedEventPublisher() {
            @Override public void publish(TransactionEnrichedEvent event) { enrichedWire.add(serialize(event)); }
            @Override public void publish(TransactionEnrichedEvent event, SourceOriginProofs.Assertion origin) {
                enrichedWire.add(serialize(event)); enrichedOriginProofs.put(event.eventId(), ORIGIN_PROOFS.signEnriched(event, origin));
            }
        };
        new TransactionFeatureEnricherService(store, new TransactionFeatureCalculator(new CurrencyAmountConverter()),
                new TransactionEnrichedEventMapper(), publisher, access, ORIGIN_PROOFS)
                .enrich(raw, ORIGIN_PROOFS.signRaw(raw, assertion));
    }
    private Scored score(TransactionEnrichedEvent event) {
        return score(event, scorer);
    }
    private Scored score(TransactionEnrichedEvent event, SourceLineageAccess access) {
        AtomicReference<ScoringContext> context = new AtomicReference<>();
        AtomicReference<TransactionScoredEvent> output = new AtomicReference<>();
        var rules = new RuleBasedFraudScoringEngine(scoringProperties());
        var emission = new EngineIntelligenceEmissionService(new EngineIntelligenceEmissionProperties(false),
                new DefaultListableBeanFactory().getBeanProvider(EngineIntelligenceDiagnosticEnrichmentPipeline.class),
                mock(EngineIntelligenceEmissionMetrics.class));
        new TransactionFraudScoringService(request -> {
            context.set(new ScoringContextFactory().from(request, ScoringMode.RULE_BASED, CLOCK.instant()));
            return rules.score(request);
        }, new TransactionScoredEventMapper(), output::set, scoringProperties(), new ScoringMetrics(metrics), emission,
                new AnalystRecommendationService(), access, null, ORIGIN_PROOFS).score(event, enrichedOriginProofs.get(event.eventId()));
        return new Scored(output.get(), context.get());
    }
    private static ScoringProperties scoringProperties() { return new ScoringProperties(0.75d, 0.90d, ScoringMode.RULE_BASED); }
    private static SourceSignatureVerifier policy(boolean revoked) {
        return new SourceSignatureVerifier(new SourceVerificationProperties(List.of(TestSourceIssuer.policy(0, revoked))), CLOCK);
    }
    private static SourceSignatureVerifier.Verified verified(byte[] payload, Map<String, Object> claims) throws Exception {
        return (SourceSignatureVerifier.Verified) policy(false).verify(TestSourceIssuer.proof(payload, claims, 0), payload,
                new SourceSignatureVerifier.Context(AttestedTransactionIngestService.PURPOSE, AttestedTransactionIngestService.AUDIENCE));
    }
    private static String encodedProof(byte[] payload, Map<String, Object> claims) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(TestSourceIssuer.proof(payload, claims, 0));
    }
    private static byte[] serialize(Object event) { return new JacksonKafkaSerializer<>().serialize("test", event); }
    private static MongoClient commitFault(MongoClient delegate, SessionCall fault) {
        return (MongoClient) Proxy.newProxyInstance(MongoClient.class.getClassLoader(), new Class<?>[] {MongoClient.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!method.getName().equals("startSession")) { return result; }
                    ClientSession session = (ClientSession) result;
                    return Proxy.newProxyInstance(ClientSession.class.getClassLoader(), new Class<?>[] {ClientSession.class},
                            (ignored, operation, parameters) -> fault.call(session, operation, parameters));
                });
    }
    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static MongoException unknownCommit() {
        MongoException failure = new MongoException(91, "TEST_ONLY_LOST_RAW_COMMIT_ACK");
        failure.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL);
        return failure;
    }
    @FunctionalInterface private interface SessionCall {
        Object call(ClientSession session, Method method, Object[] args) throws Throwable;
    }
    private static void sendToKafka(KafkaProducer<String, TransactionRawEvent> producer, String topic, TransactionRawEvent event) {
        try { producer.send(new ProducerRecord<>(topic, customerPartitionKey(event.customerId()), event)).get(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test Kafka publish interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new IllegalStateException("Test Kafka publish failed", unavailable);
        }
    }

    private static String customerPartitionKey(String customerId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("source-customer-partition/v1\u0000".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(customerId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SOURCE_CUSTOMER_PARTITION_KEY_UNAVAILABLE", unavailable);
        }
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test latch timed out"); } }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test latch interrupted", interrupted);
        }
    }
    private static <T> T roundTrip(T event, Class<T> type) { return new JacksonKafkaDeserializer<>(type).deserialize("test", serialize(event)); }
    private static <T> T change(Object event, String key, Object value, Class<T> type) {
        ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(event));
        node.set(key, JSON.valueToTree(value));
        return JSON.treeToValue(node, type);
    }
    private static TransactionRawEvent rawLinkForCustomer(TransactionRawEvent event, String eventId, String customerId) {
        ObjectNode node = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(event));
        node.put("eventId", eventId);
        node.put("customerId", customerId);
        ((ObjectNode) node.get("customerContext")).put("customerId", customerId);
        return JSON.treeToValue(node, TransactionRawEvent.class);
    }
    private record Scored(TransactionScoredEvent event, ScoringContext context) { }
    private static byte[] payload() {
        return """
                {"transactionId":"transaction-1","customerId":"customer-1","accountId":"account-1","paymentInstrumentId":"card-1",
                 "transactionTimestamp":"2026-04-20T10:15:28Z","transactionAmount":{"amount":42.00,"currency":"PLN"},
                 "merchantInfo":{"merchantId":"merchant-1","merchantName":"Shop","merchantCategoryCode":"5732","merchantCategory":"Electronics","acquiringCountryCode":"PL","channel":"ECOMMERCE","cardPresent":false,"attributes":{}},
                 "deviceInfo":{"deviceId":"device-1","fingerprint":"fp-1","ipAddress":"203.0.113.24","userAgent":"Agent","platform":"ANDROID","browser":"CHROME","trustedDevice":false,"proxyDetected":false,"vpnDetected":false,"attributes":{}},
                 "locationInfo":{"countryCode":"PL","region":"MA","city":"Warsaw","postalCode":"00-001","latitude":52.0,"longitude":21.0,"timezone":"Europe/Warsaw","highRiskCountry":false},
                 "customerContext":{"customerId":"customer-1","accountId":"account-1","segment":"PREMIUM","emailDomain":"example.com","accountAgeDays":620,"emailVerified":true,"phoneVerified":true,"homeCountryCode":"PL","preferredCurrency":"PLN","knownDeviceIds":["device-old"],"attributes":{}},
                 "transactionType":"PURCHASE","authorizationMethod":"3DS","sourceSystem":"PAYMENT_GATEWAY","traceId":"trace-1","attributes":{}}
                """.getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] payload(String transactionId, Instant timestamp) throws Exception {
        ObjectNode changed = (ObjectNode) JSON.readTree(payload());
        changed.put("transactionId", transactionId);
        changed.put("transactionTimestamp", timestamp.toString());
        return JSON.writeValueAsBytes(changed);
    }
    private static byte[] payload(String transactionId, Instant timestamp, String amount, String merchantId) throws Exception {
        return payload(transactionId, timestamp, amount, merchantId, "customer-1");
    }
    private static byte[] payload(String transactionId, Instant timestamp, String amount, String merchantId,
                                  String customerId) throws Exception {
        ObjectNode changed = (ObjectNode) JSON.readTree(payload());
        changed.put("transactionId", transactionId);
        changed.put("customerId", customerId);
        ((ObjectNode) changed.get("customerContext")).put("customerId", customerId);
        changed.put("transactionTimestamp", timestamp.toString());
        ((ObjectNode) changed.get("transactionAmount")).put("amount", new BigDecimal(amount));
        ((ObjectNode) changed.get("merchantInfo")).put("merchantId", merchantId);
        return JSON.writeValueAsBytes(changed);
    }
}
