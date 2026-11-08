package com.frauddetection.ingest.source;

import com.frauddetection.common.events.contract.*;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.source.*;
import com.frauddetection.enricher.domain.FeatureStoreSnapshot;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.persistence.FeatureStore;
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
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.frauddetection.common.events.source.SourceLineage.State.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Composition proof with real crypto, authenticated transactional Mongo, actual services/mappers and Kafka serde.
 * It does not claim broker delivery, Redis atomicity or exact post-scoring occurrence recovery (the next gate).
 */
class SourceLineagePipelineIntegrationTest {
    private static final AuthenticatedSourceMongo MONGO = new AuthenticatedSourceMongo();
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Clock CLOCK = Clock.fixed(TestSourceIssuer.NOW, ZoneOffset.UTC);
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
    private final Set<String> recorded = new HashSet<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeAll static void start() throws Exception { MONGO.start(); validation = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void stop() { if (validation != null) { validation.close(); } MONGO.close(); }

    @BeforeEach void setUp() {
        db = "lineage_" + UUID.randomUUID().toString().replace("-", "");
        ownerClient = MONGO.owner();
        verifier = policy(false);
        receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier);
        owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        enricherClient = MONGO.consumer(ownerClient, db, true);
        scorerClient = MONGO.consumer(ownerClient, db, false);
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK);
        scorer = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, verifier, CLOCK);
    }

    @AfterEach void cleanUp() { metrics.close(); ownerClient.getDatabase(db).drop(); }

    @Test void signedHttpInputSurvivesActualSerdeEnricherAndScorerContextWithoutPublicProofFields() throws Exception {
        byte[] payload = payload();
        mvc().perform(post("/api/v1/transactions/attested").contentType("application/json")
                        .header("X-Source-Proof", encodedProof(payload, Map.of())).content(payload))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sourceLineage").doesNotExist());
        var raw = lastRaw();
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

    @Test void newReceiverUuidsForSameSignedSourceRemainDistinctAndHaveTheSameOriginalReference() throws Exception {
        var first = admit(Map.of());
        var second = admit(Map.of());
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(enricher.raw(first).receiptId()).isEqualTo(enricher.raw(second).receiptId());
        assertThat(enricher.raw(first).eventCommitment()).isNotEqualTo(enricher.raw(second).eventCommitment());
        assertThat(ownerClient.getDatabase(db).getCollection("source_admission_receipts").countDocuments()).isEqualTo(1);
    }

    @Test void sameBusinessIdsAndSourceEventIdFromDifferentAuthorizedInstitutionsRemainIsolated() throws Exception {
        var a = TestSourceIssuer.policy(0, false);
        var b = new SourceVerificationProperties.TrustedKey("second-test-issuer", "institution-b", a.source(), a.sourceEpoch(),
                a.signingKeyVersion(), a.algorithm(), a.publicKeyBase64(), a.notBefore(), a.notAfter(), false, a.purpose(), a.audience(), a.policyVersion());
        verifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(a, b)), CLOCK);
        receipts = new MongoSourceReceiptStore(ownerClient, db, CLOCK,
                new SourceReceiptStore.Limits(Duration.ofHours(1), Duration.ofHours(2), 100), verifier);
        owner = new MongoSourceLineage(ownerClient, db, "source_owner", MongoSourceLineage.Stage.INGEST, verifier, CLOCK);
        enricher = new MongoSourceLineage(enricherClient, db, "enricher", MongoSourceLineage.Stage.ENRICHER, verifier, CLOCK);
        var first = admit(Map.of());
        var second = admit(Map.of("issuer", b.issuer(), "institution", b.institution()));
        assertThat(first.transactionId()).isEqualTo(second.transactionId());
        assertThat(enricher.raw(first).source().sourceEventId()).isEqualTo(enricher.raw(second).source().sourceEventId());
        assertThat(enricher.raw(first).receiptId()).isNotEqualTo(enricher.raw(second).receiptId());
        assertThat(enricher.raw(first).source().institution()).isEqualTo("institution-a");
        assertThat(enricher.raw(second).source().institution()).isEqualTo("institution-b");
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

    @Test void conflictingSameDerivedOccurrenceIsPermanentlyFencedAndLinkGrowthIsBounded() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var features = new LinkedHashMap<>(enriched.featureSnapshot());
        features.put("deviceNovelty", false);
        var changed = new TransactionEnrichedEvent(enriched.eventId(), enriched.transactionId(), enriched.correlationId(),
                enriched.customerId(), enriched.accountId(), enriched.createdAt(), enriched.transactionTimestamp(), enriched.transactionAmount(),
                enriched.merchantInfo(), enriched.deviceInfo(), enriched.locationInfo(), enriched.customerContext(), features);
        assertThat(enricher.bindEnriched(raw, changed).state()).isEqualTo(CONFLICT);
        assertThat(scorer.enriched(enriched).state()).isEqualTo(CONFLICT);
        assertThat(enricher.bindEnriched(raw, enriched).state()).isEqualTo(CONFLICT);
        for (int i = 1; i < 32; i++) { assertThat(owner.bindRaw(verified(payload(), Map.of()), enricher.raw(raw).receiptId(),
                change(raw, "eventId", "retry-" + i, TransactionRawEvent.class)).state()).isEqualTo(VERIFIED); }
        String receipt = enricher.raw(raw).receiptId();
        assertThat(owner.bindRaw(verified(payload(), Map.of()), receipt, change(raw, "eventId", "over-cap", TransactionRawEvent.class)).state()).isEqualTo(UNAVAILABLE);
        assertThat(ownerClient.getDatabase(db).getCollection("source_lineage_raw").countDocuments()).isEqualTo(32);
    }

    @Test void unchangedWireContractsReadOldFixturesAndIgnoreSelfAssertedAuthorityWithoutGrantingIt() throws Exception {
        var legacy = new TransactionRequestMapper().toEvent(JSON.readValue(payload(), IngestTransactionRequest.class), "legacy-correlation");
        assertThat(enricher.raw(roundTrip(legacy, TransactionRawEvent.class)).state()).isEqualTo(UNVERIFIED);
        enrich(legacy); var enriched = lastEnriched();
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
        enrich(raw, SourceLineageAccess.disabled()); // previous enricher behavior: no private link writer.
        var oldEnriched = lastEnriched();
        assertThat(score(oldEnriched).context.sourceLineage().state()).isEqualTo(UNVERIFIED);
        recorded.clear();
        enrich(raw);
        var currentEnriched = lastEnriched();
        assertThat(currentEnriched.featureSnapshot()).isEqualTo(oldEnriched.featureSnapshot());
        assertThat(score(currentEnriched).context.sourceLineage().state()).isEqualTo(VERIFIED);
        var oldScorer = score(currentEnriched, SourceLineageAccess.disabled());
        assertThat(oldScorer.context.sourceLineage().state()).isEqualTo(UNVERIFIED);
        assertThat(oldScorer.event.eventContractVersion()).isEqualTo(2);
    }

    @Test void receiptExpiryAndStoreOutageCannotBecomeTrustedWhileOrdinaryScoringStillRuns() throws Exception {
        var raw = admit(Map.of()); enrich(raw); var enriched = lastEnriched();
        var late = new MongoSourceLineage(scorerClient, db, "scorer", MongoSourceLineage.Stage.SCORER, verifier,
                Clock.offset(CLOCK, Duration.ofHours(3)));
        assertThat(late.enriched(enriched).state()).isNotEqualTo(VERIFIED);
        scorerClient.close();
        assertThat(scorer.enriched(enriched).state()).isEqualTo(UNAVAILABLE);
        var scored = score(enriched);
        assertThat(scored.context.sourceLineage().state()).isEqualTo(UNAVAILABLE);
        assertThat(scored.event.eventContractVersion()).isEqualTo(2);
    }

    @Test void validProofWithDisabledSidecarAndUnknownPublishAcknowledgementNeverReportsAccepted() throws Exception {
        var disabled = new AttestedTransactionIngestService(new TransactionRequestMapper(), verifier, (SourceReceiptStore) null,
                null, publisher(), new CorrelationIdProvider(), new KafkaTopicProperties("transactions.raw"), validation.getValidator());
        assertThatThrownBy(() -> disabled.ingest(encodedProof(payload(), Map.of()), payload()))
                .isInstanceOf(SourceIngestException.class).hasMessage("SOURCE_LINEAGE_UNAVAILABLE");
        var uncertain = service(event -> { rawWire.add(serialize(event)); throw new IllegalStateException("lost acknowledgement"); });
        assertThatThrownBy(() -> uncertain.ingest(encodedProof(payload(), Map.of()), payload()))
                .hasMessage("SOURCE_RAW_PUBLICATION_UNRESOLVED");
        assertThat(enricher.raw(lastRaw()).state()).isEqualTo(VERIFIED); // durable facts may reconcile; delivery not claimed.
    }

    private MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new AttestedTransactionIngestController(service(publisher())))
            .setControllerAdvice(new TransactionIngestExceptionHandler()).build(); }
    private AttestedTransactionIngestService service(TransactionRawEventProducer producer) {
        return new AttestedTransactionIngestService(new TransactionRequestMapper(), verifier, receipts, owner, producer,
                new CorrelationIdProvider(), new KafkaTopicProperties("transactions.raw"), validation.getValidator());
    }
    private TransactionRawEventProducer publisher() { return event -> rawWire.add(serialize(event)); }
    private TransactionRawEvent admit(Map<String, Object> claims) throws Exception {
        service(publisher()).ingest(encodedProof(payload(), claims), payload()); return lastRaw();
    }
    private TransactionRawEvent lastRaw() { return new JacksonKafkaDeserializer<>(TransactionRawEvent.class).deserialize("transactions.raw", rawWire.getLast()); }
    private TransactionEnrichedEvent lastEnriched() { return new JacksonKafkaDeserializer<>(TransactionEnrichedEvent.class).deserialize("transactions.enriched", enrichedWire.getLast()); }
    private void enrich(TransactionRawEvent raw) {
        enrich(raw, enricher);
    }
    private void enrich(TransactionRawEvent raw, SourceLineageAccess access) {
        FeatureStore store = new FeatureStore() {
            public boolean hasRecordedTransaction(TransactionRawEvent event) { return recorded.contains(event.customerId() + "/" + event.transactionId()); }
            public FeatureStoreSnapshot loadSnapshot(TransactionRawEvent event) { return new FeatureStoreSnapshot(0, BigDecimal.ZERO, List.of(), 0, null, false); }
            public void recordTransaction(TransactionRawEvent event) { recorded.add(event.customerId() + "/" + event.transactionId()); }
        };
        new TransactionFeatureEnricherService(store, new TransactionFeatureCalculator(new CurrencyAmountConverter()),
                new TransactionEnrichedEventMapper(), event -> enrichedWire.add(serialize(event)), access).enrich(raw);
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
                new AnalystRecommendationService(), access).score(event);
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
