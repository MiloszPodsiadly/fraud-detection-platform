package com.frauddetection.ingest.source;

import com.frauddetection.common.events.contract.*;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.source.*;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
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
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        enricherClient = MONGO.consumer(ownerClient, db, true);
        scorerClient = MONGO.consumer(ownerClient, db, false);
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
        scorer = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, verifier, CLOCK);
    }

    @AfterEach void cleanUp() { metrics.close(); ownerClient.getDatabase(db).drop(); }

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
                .doesNotContainKeys("attempts", "leaseToken", "recoveryEpoch", "preparedAt");
        var indexes = database.getCollection("source_enriched_publications").listIndexes().into(new ArrayList<>());
        assertThat(indexes).extracting(row -> row.getString("name"))
                .containsExactlyInAnyOrder("_id_", "enriched_publication_recovery", "enriched_publication_history");
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

    @Test void enrichedRecoveryMigrationUpgradesThePreviousHistoryValidator() {
        var database = ownerClient.getDatabase(db);
        Document collection = database.listCollections().filter(eq("name", "source_enriched_publications")).first();
        Document options = collection.get("options", Document.class);
        Document previousValidator = Document.parse(options.get("validator", Document.class).toJson());
        Document jsonSchema = previousValidator.get("$jsonSchema", Document.class);
        Document properties = jsonSchema.get("properties", Document.class);
        properties.get("historyState", Document.class).put("enum", List.of("PENDING", "APPLIED"));
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
        String damagedId = preparePublishedWithAppliedHistory(admit(Map.of()));
        String healthyId = preparePublishedWithAppliedHistory(admit(Map.of("sourceEpoch", "epoch-b")));
        var publications = ownerClient.getDatabase(db).getCollection("source_enriched_publications");
        publications.updateOne(eq("_id", damagedId), new Document("$set", new Document("historyState", "PENDING")
                .append("historyAppliedAt", null).append("preparedAt", Date.from(TestSourceIssuer.NOW.minusSeconds(10)))
                .append("ciphertext", new org.bson.types.Binary(new byte[] {1, 2, 3}))));
        publications.updateOne(eq("_id", healthyId), new Document("$set", new Document("historyState", "PENDING")
                .append("historyAppliedAt", null).append("preparedAt", Date.from(TestSourceIssuer.NOW))));

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
        var raw = admit(Map.of("sourceEventId", "tampered-enriched-recovery"));
        var candidate = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(raw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        var prepared = enricher.prepareEnrichedPublication(raw, candidate);
        Document publication = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find().first();
        byte[] ciphertext = publication.get("ciphertext", org.bson.types.Binary.class).getData().clone();
        ciphertext[ciphertext.length - 1] ^= 1;
        ownerClient.getDatabase(db).getCollection("source_enriched_publications").updateOne(eq("_id", publication.getString("_id")),
                set("ciphertext", new org.bson.types.Binary(ciphertext)));
        assertThat(enricher.claimNextEnrichedPublications(1)).isEmpty();
        publication = ownerClient.getDatabase(db).getCollection("source_enriched_publications").find(eq("_id", publication.getString("_id"))).first();
        assertThat(publication).containsEntry("state", "QUARANTINED").containsEntry("failureCode", "PAYLOAD_UNAVAILABLE");

        var revokedRaw = admit(Map.of("sourceEventId", "revoked-enriched-recovery"));
        var revokedCandidate = new TransactionEnrichedEventMapper().toAttestedEvent(revokedRaw,
                new TransactionFeatureCalculator(new CurrencyAmountConverter()).calculate(revokedRaw,
                        new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false)));
        enricher.prepareEnrichedPublication(revokedRaw, revokedCandidate);
        var revokedWorker = newEnricher(CLOCK, new SourceSignatureVerifier(
                new SourceVerificationProperties(List.of(TestSourceIssuer.policy(0, true))), CLOCK));
        assertThat(revokedWorker.claimNextEnrichedPublications(2)).isEmpty();
        Document revokedRow = ownerClient.getDatabase(db).getCollection("source_enriched_publications")
                .find(eq("rawCommitment", SourceEventCommitment.raw(revokedRaw))).first();
        assertThat(revokedRow).containsEntry("state", "QUARANTINED").containsEntry("failureCode", "AUTHORITY_DENIED");
        assertThat(SourceEventCommitment.enriched(prepared.event())).isNotEqualTo(SourceEventCommitment.enriched(revokedCandidate));
    }

    private MongoSourceLineage newEnricher(Clock clock, SourceSignatureVerifier verifier) {
        return new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, clock,
                new SourceEnrichedPublicationCipher("enriched-key-1", Map.of("enriched-key-1", ENRICHED_KEY)));
    }

    @Test void redriveOfExactBytesIsIdempotentButNoBusinessDeduplicationCrossBindsSources() throws Exception {
        var first = admit(Map.of());
        enrich(first);
        var enriched = lastEnriched();
        assertThat(enricher.bindEnriched(first, enriched).state()).isEqualTo(VERIFIED);
        assertThat(scorer.enriched(roundTrip(enriched, TransactionEnrichedEvent.class)).source().sourceEventId()).isEqualTo("source-event-42");
        var second = admit(Map.of("sourceEventId", "another-source-event"));
        assertThat(enricher.raw(second).source().sourceEventId()).isEqualTo("another-source-event");
        assertThat(enricher.raw(second).receiptId()).isNotEqualTo(enricher.raw(first).receiptId());
        enrich(second); // existing customer/transaction business dedup: no second output, no invented history completeness.
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
        for (int i = 1; i < 32; i++) { assertThat(owner.bindRaw(verified(payload(), Map.of()), enricher.raw(raw).receiptId(),
                change(raw, "eventId", "retry-" + i, TransactionRawEvent.class)).state()).isEqualTo(VERIFIED); }
        String receipt = enricher.raw(raw).receiptId();
        assertThat(owner.bindRaw(verified(payload(), Map.of()), receipt, change(raw, "eventId", "over-cap", TransactionRawEvent.class)).state()).isEqualTo(UNAVAILABLE);
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
        service(publisher()).ingest(encodedProof(payload(), claims), payload());
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
        try { producer.send(new ProducerRecord<>(topic, event.transactionId(), event)).get(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test Kafka publish interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new IllegalStateException("Test Kafka publish failed", unavailable);
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
}
