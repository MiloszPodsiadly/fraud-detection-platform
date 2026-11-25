package com.frauddetection.enricher.merchant;

import com.frauddetection.common.events.contract.TransactionEnrichedEvent;
import com.frauddetection.common.events.contract.TransactionRawEvent;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.model.MerchantInfo;
import com.frauddetection.common.events.source.*;
import com.frauddetection.common.testsupport.container.KafkaImageReference;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.enricher.merchant.MerchantObservationStore.*;
import com.frauddetection.ingest.source.DeviceTestSourceReceipts;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.BsonValue;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real authenticated source receipts plus a Mongo replica set; all keys and feed authority are test-only. */
@Tag("UNTRUSTED_TEST_ONLY")
class MerchantVerifiedSourceAdmissionEndToEndIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Limits LIMITS = new Limits(Duration.ofHours(1), Duration.ofDays(2), Duration.ofDays(4),
            Duration.ofDays(3), Duration.ofMinutes(2), 100, 100, 100, 1_000);
    private static final TestMongo MONGO = new TestMongo();
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaImageReference.dockerImageName())
            .withImagePullPolicy(KafkaImageReference.localOnlyPullPolicy());

    private final KeyPair sourceKey = key();
    private final KeyPair observationKey = key();
    private final KeyPair coverageKey = key();
    private final KeyPair rotatedCoverageKey = key();
    private final KeyPair thirdCoverageKey = key();
    private final byte[] pseudonymKey = randomKey();
    private final MerchantPseudonyms pseudonyms = new MerchantPseudonyms(pseudonymKey, 1, 1);
    private final MerchantAssertionTrustPolicy.SourceIdentity identity =
            new MerchantAssertionTrustPolicy.SourceIdentity("source-issuer", "institution-a", "BANK", "epoch-a", 1);
    private final MerchantAssertionTrustPolicy.Population population = new MerchantAssertionTrustPolicy.Population(
            "merchant-observation-feed-v1", "all-merchant-observations-v1", 1,
            "1".repeat(64), "2".repeat(64), "3".repeat(64), NOW.minus(Duration.ofDays(30)));
    private final String observationGrantId = "merchant-observation-grant-v1";
    private final String coverageGrantId = "merchant-coverage-grant-v1";
    private final SourceVerificationProperties.TrustedKey originKey = trusted("source-issuer", "origin-v1",
            "TRANSACTION_SOURCE_ATTESTATION", "transaction-ingest", sourceKey, "source-policy-v1");
    private final SourceVerificationProperties origins = new SourceVerificationProperties(List.of(originKey));
    private final SourceSignatureVerifier originVerifier = new SourceSignatureVerifier(origins, CLOCK);
    private final List<MerchantAssertionTrustPolicy.Grant> grants = List.of(
            new MerchantAssertionTrustPolicy.Grant(MerchantAssertionVerifier.Role.OBSERVATION, observationGrantId, 1,
                    trusted("merchant-observation-issuer", "observation-v1", MerchantAssertionVerifier.OBSERVATION_PURPOSE,
                            MerchantAssertionTrustPolicy.AUDIENCE, observationKey, "merchant-policy-v1"), identity, population,
                    new MerchantAssertionTrustPolicy.MerchantMapping(pseudonyms.mappingVersion(), pseudonyms.mappingCommitment())),
            new MerchantAssertionTrustPolicy.Grant(MerchantAssertionVerifier.Role.COVERAGE, coverageGrantId, 1,
                    trusted("merchant-coverage-issuer", "coverage-v1", MerchantAssertionVerifier.COVERAGE_PURPOSE,
                            MerchantAssertionTrustPolicy.AUDIENCE, coverageKey, "merchant-policy-v1"), identity, population, null));

    private MongoClient ownerClient;
    private MongoClient enricherClient;
    private MongoClient occurrenceWriterClient;
    private MongoClient occurrenceReaderClient;
    private MongoClient merchantReaderClient;
    private MongoSourceLineage owner;
    private MongoSourceLineage enricher;
    private MongoSourceLineage diagnosticSource;
    private MongoSourceOccurrenceStore occurrenceWriter;
    private SourceOccurrenceAccess occurrenceReader;
    private String database;
    private String scoredTopic;

    @BeforeAll static void startMongo() throws Exception { MONGO.start(); }
    @AfterAll static void stopMongo() { MONGO.close(); }

    @BeforeEach void setUp() {
        database = "merchant_verified_" + UUID.randomUUID().toString().replace("-", "");
        ownerClient = MONGO.owner();
        enricherClient = MONGO.enricher(ownerClient, database);
        owner = new MongoSourceLineage(ownerClient, database, "source_owner", MongoSourceLineage.Stage.INGEST, originVerifier, CLOCK);
        enricher = new MongoSourceLineage(enricherClient, database, "enricher", MongoSourceLineage.Stage.ENRICHER, originVerifier, CLOCK);
        occurrenceWriterClient = MONGO.occurrenceWriter(ownerClient, database);
        occurrenceReaderClient = MONGO.occurrenceReader(ownerClient, database);
        merchantReaderClient = MONGO.merchantReader(ownerClient, database);
        diagnosticSource = new MongoSourceLineage(occurrenceReaderClient, database, "merchant_source_reader",
                MongoSourceLineage.Stage.SCORER, originVerifier, CLOCK);
        SourceOccurrenceCipher cipher = new SourceOccurrenceCipher("test-key",
                Map.of("test-key", Base64.getEncoder().encodeToString(randomKey())));
        occurrenceWriter = new MongoSourceOccurrenceStore(occurrenceWriterClient, database, "merchant_scored_writer",
                originVerifier, CLOCK, cipher, Duration.ofHours(1));
        occurrenceReader = MongoSourceOccurrenceStore.readOnly(occurrenceReaderClient, database, "merchant_source_reader",
                originVerifier, CLOCK, cipher, new SourceSignatureVerifier.Context(originKey.purpose(), originKey.audience()));
    }

    @AfterEach void cleanup() { if (ownerClient != null && database != null) { ownerClient.getDatabase(database).drop(); } }

    @Test
    void signedSourceWideMixedMerchantFeedCommitsPreEventSnapshotAndReplaysAfterRestart() {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent first = sourceEvent(1, "merchant-a", 180);
        SourceEvent otherMerchant = sourceEvent(2, "merchant-b", 120);
        SourceEvent current = sourceEvent(3, "merchant-a", 60);
        var firstTicket = stage(admission, first, 1);
        var otherTicket = stage(admission, otherMerchant, 2);
        var currentTicket = stage(admission, current, 3);
        Signed coverage = coverage(3, current.raw.createdAt());

        admission.seal(currentTicket, coverage.proof, coverage.payload);
        assertThat(admission.commit(firstTicket).snapshot().priorCount()).isZero();
        assertThat(admission.commit(otherTicket).snapshot().priorCount()).isZero();
        CommitResult committed = admission.commit(currentTicket);
        assertThat(committed.status()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(committed.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(committed.snapshot().priorCount()).isEqualTo(1);

        var merchantCollections = ownerClient.getDatabase(database).listCollectionNames()
                .into(new ArrayList<>()).stream().filter(name -> name.startsWith("merchant_")).toList();
        assertThat(merchantCollections).containsExactlyInAnyOrder(
                MongoMerchantObservationSchema.CANDIDATES, MongoMerchantObservationSchema.HISTORY,
                MongoMerchantObservationSchema.MANIFEST, MongoMerchantObservationSchema.COVERAGE,
                MongoMerchantObservationSchema.MEMBERSHIP, MongoMerchantObservationSchema.MEMBERSHIP_TIPS);
        String currentId = MerchantObservationDocumentCodec.eventId(pseudonyms.scope(sourceScope(current)),
                pseudonyms.sourceEvent("institution-a", "BANK", current.raw.eventId()));
        Document persisted = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MANIFEST)
                .find(eq("_id", currentId)).first();
        assertThat(persisted).isNotNull();
        String evidenceCommitment = persisted.getString("evidenceCommitment");
        assertThat(evidenceCommitment).matches("[0-9a-f]{64}");
        assertThat(persisted.get("observationAnchor", Document.class)).containsEntry("commitment", evidenceCommitment)
                .containsKeys("assertion", "proof", "role", "version");
        assertThat(persisted.toJson()).doesNotContain("transaction-3", "merchant-a", "customer-3");

        VerifiedMerchantAdmission restarted = adapter();
        var replay = stage(restarted, current, 3);
        assertThat(replay.status()).isEqualTo(StageResult.EXISTING);
        restarted.seal(replay, coverage.proof, coverage.payload);
        assertThat(restarted.commit(replay).snapshot()).isEqualTo(committed.snapshot());
        assertThat(ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.HISTORY).countDocuments()).isEqualTo(3);
        assertThat(ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MANIFEST).countDocuments()).isEqualTo(3);
    }

    @Test
    void alteredPersistedHistoricalAssertionCannotProduceACompleteWindowAfterRestart() {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent first = sourceEvent(1, "merchant-a", 180);
        SourceEvent second = sourceEvent(2, "merchant-a", 120);
        SourceEvent current = sourceEvent(3, "merchant-a", 60);
        stage(admission, first, 1);
        stage(admission, second, 2);
        var currentTicket = stage(admission, current, 3);
        Signed coverage = coverage(3, current.raw.createdAt());
        admission.seal(currentTicket, coverage.proof, coverage.payload);
        admission.commit(stage(admission, first, 1));
        admission.commit(stage(admission, second, 2));

        String firstId = MerchantObservationDocumentCodec.eventId(pseudonyms.scope(sourceScope(first)),
                pseudonyms.sourceEvent("institution-a", "BANK", first.raw.eventId()));
        // Use the signed assertion anchor itself as the tamper target; keep its commitment to exercise byte verification.
        ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MANIFEST)
                .updateOne(eq("_id", firstId), set("observationAnchor.assertion", "AA=="));

        var ledger = ownerClient.getDatabase(database);
        String currentId = MerchantObservationDocumentCodec.eventId(pseudonyms.scope(sourceScope(current)),
                pseudonyms.sourceEvent("institution-a", "BANK", current.raw.eventId()));
        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(current)));
        Document candidateBefore = ledger.getCollection(MongoMerchantObservationSchema.CANDIDATES)
                .find(eq("_id", currentId)).first();
        Document manifestBefore = ledger.getCollection(MongoMerchantObservationSchema.MANIFEST)
                .find(eq("_id", currentId)).first();
        Document sourceBefore = ledger.getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", scopeId)).first();
        Document membershipBefore = ledger.getCollection(MongoMerchantObservationSchema.MEMBERSHIP)
                .find(eq("_id", currentId)).first();
        long historyCountBefore = ledger.getCollection(MongoMerchantObservationSchema.HISTORY).countDocuments();
        long membershipCountBefore = ledger.getCollection(MongoMerchantObservationSchema.MEMBERSHIP).countDocuments();
        assertThat(candidateBefore).isNotNull().containsEntry("status", "STAGED");
        assertThat(manifestBefore).isNotNull();
        assertThat(manifestBefore.get("scoredBinding")).isNull();
        assertThat(sourceBefore).isNotNull();

        VerifiedMerchantAdmission restarted = adapter();
        var replay = stage(restarted, current, 3);
        assertThatThrownBy(() -> restarted.commit(replay)).hasMessage("MERCHANT_STORE_NOT_ADMITTED");
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.CANDIDATES).find(eq("_id", currentId)).first())
                .isEqualTo(candidateBefore);
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.MANIFEST).find(eq("_id", currentId)).first())
                .isEqualTo(manifestBefore);
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.COVERAGE).find(eq("_id", scopeId)).first())
                .isEqualTo(sourceBefore);
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.MEMBERSHIP).find(eq("_id", currentId)).first())
                .isEqualTo(membershipBefore);
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.HISTORY).countDocuments()).isEqualTo(historyCountBefore);
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.MEMBERSHIP).countDocuments())
                .isEqualTo(membershipCountBefore);
        assertThat(ledger.getCollection(MongoMerchantObservationSchema.MANIFEST).find(eq("_id", currentId)).first()
                .get("scoredBinding")).isNull();
        assertThat(ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.HISTORY)
                .find(eq("_id", firstId)).first()).isNotNull();
    }

    @Test
    void signedCoverageCannotSkipASourceWideFeedPosition() {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent second = sourceEvent(2, "merchant-a", 60);
        var ticket = stage(admission, second, 2);
        Signed coverage = coverage(2, second.raw.createdAt());

        assertThatThrownBy(() -> admission.seal(ticket, coverage.proof, coverage.payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MERCHANT_STORE_SOURCE_SEQUENCE_GAP");

        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(second)));
        Document persisted = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", scopeId)).first();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getLong("sealedSequence")).isZero();
        assertThat(persisted.get("coverageAnchor")).isNull();
    }

    @Test
    void exactReplayIsIdempotentAndConflictingSignedReplayInvalidatesTheFeed() {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent first = sourceEvent(1, "merchant-a", 120);
        var accepted = stage(admission, first, 1);
        assertThat(stage(admission, first, 1).status()).isEqualTo(StageResult.EXISTING);

        assertThatThrownBy(() -> stage(admission, first, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MERCHANT_STORE_IDENTITY_CONFLICT");

        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(first)));
        Document persisted = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", scopeId)).first();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getBoolean("invalidated")).isTrue();
        assertThat(accepted.status()).isEqualTo(StageResult.CREATED);
    }

    @Test
    void stageBWaitsForPublishedInitialScoreAndReplaysTheExactBindingAfterRestart() {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent current = sourceEvent(1, "merchant-a", 60);
        var ticket = stage(admission, current, 1);
        Signed seal = coverage(1, current.raw.createdAt());
        admission.seal(ticket, seal.proof, seal.payload);
        CommitResult committed = admission.commit(ticket);
        assertThat(committed.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(committed.snapshot().priorCount()).isZero();

        TransactionScoredEvent candidate = score(current, "score-initial-1");
        var pending = occurrenceWriter.prepare(current.enriched, MongoSourceOccurrenceStore.INITIAL_RUN, candidate);
        assertThat(pending.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PENDING);
        SourceOccurrenceAccess.Reference reference = reference(candidate);
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader, reference))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
        assertThat(manifest(current).get("scoredBinding")).isNull();

        var claim = occurrenceWriter.claim(pending.id());
        assertThat(claim.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHING);
        // This test exercises the persisted source-occurrence acknowledgement boundary; P07 covers the real Kafka path.
        var published = occurrenceWriter.confirm(pending.id(), claim.event(), claim.claimId());
        assertThat(published.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHED);
        SourceScoredOccurrence exact = occurrenceReader.find(enricher.enriched(current.enriched), reference).orElseThrow();
        assertThat(exact.runId()).isEqualTo(MongoSourceOccurrenceStore.INITIAL_RUN);
        assertThat(exact.scoredEventId()).isEqualTo(reference.eventId());
        assertThat(exact.scoredCreatedAt()).isEqualTo(reference.createdAt());
        assertThat(exact.scoredFingerprint()).isEqualTo(reference.fingerprint());

        admission.bindScored(ticket, occurrenceReader, reference);
        Document firstBinding = manifest(current).get("scoredBinding", Document.class);
        assertThat(firstBinding).containsEntry("eventId", reference.eventId())
                .containsEntry("createdAt", reference.createdAt().toString())
                .containsEntry("fingerprint", reference.fingerprint())
                .containsEntry("runId", MongoSourceOccurrenceStore.INITIAL_RUN)
                .containsKeys("sourceScopeCommitment", "coverageCommitment", "receiptId", "rawCommitment",
                        "enrichedCommitment", "merchantCommitment", "inputCommitment", "snapshotCommitment",
                        "historyCommitment", "commitment");
        assertThat(firstBinding.toJson()).doesNotContain("merchant-a", "transaction-1", "customer-1");
        assertThat(admission.commit(ticket).snapshot()).isEqualTo(committed.snapshot());

        admission.bindScored(ticket, occurrenceReader, reference);
        VerifiedMerchantAdmission restarted = adapter();
        var replay = stage(restarted, current, 1);
        assertThat(replay.status()).isEqualTo(StageResult.EXISTING);
        restarted.seal(replay, seal.proof, seal.payload);
        assertThat(restarted.commit(replay).snapshot()).isEqualTo(committed.snapshot());
        restarted.bindScored(replay, occurrenceReader, reference);
        assertThat(manifest(current).get("scoredBinding", Document.class)).isEqualTo(firstBinding);
    }

    @Test
    void privateReadKeepsTheInitialScoredBindingAfterCoverageAdvancesToAnIndependentKey() {
        List<MerchantAssertionTrustPolicy.Grant> rotatedGrants = rotatedCoverageGrants(false);
        VerifiedMerchantAdmission admission = adapter(rotatedGrants);
        SourceEvent first = sourceEvent(1, "merchant-a", 60);
        Staged firstStaged = stageWithEvidence(admission, first, 1);
        MerchantAssertionTrustPolicy.Grant firstCoverageGrant = rotatedGrants.get(1);
        Signed c1 = signedCoverage(1, 1, 1, null, NOW.minus(Duration.ofHours(2)),
                first.raw.createdAt(), firstCoverageGrant, coverageKey);
        admission.seal(firstStaged.ticket, c1.proof, c1.payload);
        CommitResult firstSnapshot = admission.commit(firstStaged.ticket);
        PersistedScore published = publish(first, MongoSourceOccurrenceStore.INITIAL_RUN, "score-c1-1");
        admission.bindScored(firstStaged.ticket, occurrenceReader, published.reference);

        SourceEvent second = sourceEvent(2, "merchant-b", 30);
        VerifiedMerchantAdmission.Ticket secondTicket = stage(admission, second, 2);
        MerchantAssertionVerifier.VerifiedCoverage c1Evidence = verifiedCoverage(c1, first, rotatedGrants, null, null);
        String c1Commitment = c1Evidence.assertionCommitment();
        Signed c2 = signedCoverage(2, 2, 2, c1Commitment, NOW.minus(Duration.ofMinutes(30)),
                second.raw.createdAt(), rotatedGrants.get(2), rotatedCoverageKey);
        admission.seal(secondTicket, c2.proof, c2.payload);

        SourceEvent third = sourceEvent(3, "merchant-c", 10);
        VerifiedMerchantAdmission.Ticket thirdTicket = stage(admission, third, 3);
        MerchantAssertionVerifier.VerifiedCoverage c2Evidence = verifiedCoverage(c2, second, rotatedGrants, c1, first);
        Signed c3 = signedCoverage(3, 3, 3, c2Evidence.assertionCommitment(), NOW.minus(Duration.ofMinutes(10)),
                third.raw.createdAt(), rotatedGrants.get(3), thirdCoverageKey);
        admission.seal(thirdTicket, c3.proof, c3.payload);

        PrivateMerchantDiagnosticReader.ReadGrant grant = readGrant(first, "merchant-a");
        PrivateMerchantDiagnosticReader reader = reader(grant, new AtomicBoolean(true), () -> { }, System::nanoTime,
                CLOCK, rotatedGrants);
        assertThat(firstSnapshot.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(reader.evaluate(request(first, firstStaged, published.reference)).outcome())
                .isEqualTo(PrivateMerchantDiagnosticReader.Outcome.EVALUATED);

        VerifiedMerchantAdmission restarted = adapter(rotatedGrants);
        VerifiedMerchantAdmission.Ticket replay = stage(restarted, first, 1);
        assertThat(replay.status()).isEqualTo(StageResult.EXISTING);
        assertThat(restarted.commit(replay).snapshot()).isEqualTo(firstSnapshot.snapshot());
        restarted.bindScored(replay, occurrenceReader, published.reference);
        PrivateMerchantDiagnosticReader restartedReader = reader(grant, new AtomicBoolean(true), () -> { },
                System::nanoTime, CLOCK, rotatedGrants);
        assertThat(restartedReader.evaluate(request(first, firstStaged, published.reference)).outcome())
                .isEqualTo(PrivateMerchantDiagnosticReader.Outcome.EVALUATED);

        admission.commit(secondTicket);
        admission.commit(thirdTicket);

        SourceEvent unsupported = sourceEvent(4, "merchant-d", 1);
        VerifiedMerchantAdmission.Ticket unsupportedTicket = stage(restarted, unsupported, 4);
        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(unsupported)));
        String c3Commitment = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", scopeId)).first().get("coverageAnchor", Document.class).getString("commitment");
        Signed c4 = signedCoverage(4, 4, 4, c3Commitment, NOW.minus(Duration.ofMinutes(5)),
                NOW, rotatedGrants.get(3), thirdCoverageKey);
        restarted.seal(unsupportedTicket, c4.proof, c4.payload);
        CommitResult unsupportedWindow = restarted.commit(unsupportedTicket);
        assertThat(unsupportedWindow.snapshot().availability()).isEqualTo(Availability.UNAVAILABLE);
    }

    @Test
    void verifiedCoveragePrefixCompactsIntoAFenceAndAcceptsTheNextGeneration() {
        Limits compactLimits = new Limits(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(31),
                Duration.ofSeconds(30), Duration.ofSeconds(1), 100, 100, 100, 1_000);
        MutableClock clock = new MutableClock(NOW);
        VerifiedMerchantAdmission admission = adapter(grants, clock, compactLimits);
        SourceEvent first = sourceEvent(1, "merchant-a", 2);
        Staged firstStaged = stageWithEvidence(admission, first, 1);
        MerchantAssertionTrustPolicy.Grant coverageGrant = grants.get(1);
        Signed c1 = signedCoverage(1, 1, 1, null, first.raw.createdAt(), coverageGrant, coverageKey);
        admission.seal(firstStaged.ticket, c1.proof, c1.payload);
        admission.commit(firstStaged.ticket);

        SourceEvent second = sourceEvent(2, "merchant-b", 1);
        Staged secondStaged = stageWithEvidence(admission, second, 2);
        String c1Commitment = verifiedCoverageCommitment(c1, first, grants);
        Signed c2 = signedCoverage(2, 2, 2, c1Commitment, second.raw.createdAt(), coverageGrant, coverageKey);
        admission.seal(secondStaged.ticket, c2.proof, c2.payload);
        admission.commit(secondStaged.ticket);
        String c2Commitment = verifiedCoverage(c2, second, grants, c1, first).assertionCommitment();

        clock.set(NOW.plusSeconds(32));
        SourceEvent current = sourceEvent(3, "merchant-c", -10);
        Staged currentStaged = stageWithEvidence(admission, current, 3);

        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(current)));
        Document source = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", scopeId)).first();
        assertThat(source.getLong("compactedThroughSequence")).isEqualTo(2L);
        assertThat(source.get("coverageFence", Document.class).getLong("generation")).isEqualTo(2L);
        assertThat(ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                .countDocuments(new Document("kind", MerchantObservationDocumentCodec.COVERAGE_GENERATION_KIND))).isZero();

        Signed c3 = signedCoverage(3, 3, 3, c2Commitment, current.raw.createdAt(), coverageGrant, coverageKey);
        admission.seal(currentStaged.ticket, c3.proof, c3.payload);
        Snapshot snapshot = admission.commit(currentStaged.ticket).snapshot();
        assertThat(snapshot.availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        assertThat(snapshot.priorCount()).isZero();
        assertThat(ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                .countDocuments(new Document("kind", MerchantObservationDocumentCodec.COVERAGE_GENERATION_KIND))).isOne();
    }

    @Test
    void revokingTheFirstCoverageSignerMakesItsHistoricalDiagnosticUnavailable() {
        List<MerchantAssertionTrustPolicy.Grant> activeGrants = rotatedCoverageGrants(false);
        VerifiedMerchantAdmission admission = adapter(activeGrants);
        SourceEvent first = sourceEvent(1, "merchant-a", 60);
        Staged firstStaged = stageWithEvidence(admission, first, 1);
        Signed c1 = signedCoverage(1, 1, 1, null, first.raw.createdAt(), activeGrants.get(1), coverageKey);
        admission.seal(firstStaged.ticket, c1.proof, c1.payload);
        admission.commit(firstStaged.ticket);
        PersistedScore published = publish(first, MongoSourceOccurrenceStore.INITIAL_RUN, "score-revoked-c1-1");
        admission.bindScored(firstStaged.ticket, occurrenceReader, published.reference);

        SourceEvent second = sourceEvent(2, "merchant-b", 30);
        VerifiedMerchantAdmission.Ticket secondTicket = stage(admission, second, 2);
        String c1Commitment = verifiedCoverageCommitment(c1, first, activeGrants);
        Signed c2 = signedCoverage(2, 2, 2, c1Commitment, second.raw.createdAt(), activeGrants.get(2), rotatedCoverageKey);
        admission.seal(secondTicket, c2.proof, c2.payload);

        List<MerchantAssertionTrustPolicy.Grant> revokedFirstGrant = rotatedCoverageGrants(true);
        PrivateMerchantDiagnosticReader.ReadGrant grant = readGrant(first, "merchant-a");
        PrivateMerchantDiagnosticReader reader = reader(grant, new AtomicBoolean(true), () -> { }, System::nanoTime,
                CLOCK, revokedFirstGrant);
        assertThat(reader.evaluate(request(first, firstStaged, published.reference)).outcome())
                .isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
    }

    @Test
    void missingHistoricalCoverageGenerationMakesTheBoundDiagnosticUnavailableAfterRestart() {
        List<MerchantAssertionTrustPolicy.Grant> activeGrants = rotatedCoverageGrants(false);
        VerifiedMerchantAdmission admission = adapter(activeGrants);
        SourceEvent first = sourceEvent(1, "merchant-a", 60);
        Staged firstStaged = stageWithEvidence(admission, first, 1);
        Signed c1 = signedCoverage(1, 1, 1, null, first.raw.createdAt(), activeGrants.get(1), coverageKey);
        admission.seal(firstStaged.ticket, c1.proof, c1.payload);
        admission.commit(firstStaged.ticket);
        PersistedScore published = publish(first, MongoSourceOccurrenceStore.INITIAL_RUN, "score-missing-c1-1");
        admission.bindScored(firstStaged.ticket, occurrenceReader, published.reference);

        SourceEvent second = sourceEvent(2, "merchant-b", 30);
        VerifiedMerchantAdmission.Ticket secondTicket = stage(admission, second, 2);
        String c1Commitment = verifiedCoverageCommitment(c1, first, activeGrants);
        Signed c2 = signedCoverage(2, 2, 2, c1Commitment, second.raw.createdAt(), activeGrants.get(2), rotatedCoverageKey);
        admission.seal(secondTicket, c2.proof, c2.payload);

        String firstGenerationId = MerchantObservationDocumentCodec.coverageGenerationId(
                pseudonyms.scope(first.origin.scope()), 1);
        long deleted = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MEMBERSHIP_TIPS)
                .deleteOne(eq("_id", firstGenerationId)).getDeletedCount();
        assertThat(deleted).isOne();

        PrivateMerchantDiagnosticReader reader = reader(readGrant(first, "merchant-a"), new AtomicBoolean(true),
                () -> { }, System::nanoTime, CLOCK, activeGrants);
        assertThat(reader.evaluate(request(first, firstStaged, published.reference)).outcome())
                .isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
    }

    @Test
    void historicalSourceLineageReadsStayAtFourQueriesPerThousandBindings() {
        SourceEvent event = sourceEvent(1, "merchant-a", 60);
        MongoSourceLineage.EnrichedBinding binding = new MongoSourceLineage.EnrichedBinding(
                SourceReceiptIdentity.identity(event.origin.scope()), SourceEventCommitment.raw(event.raw),
                SourceEventCommitment.enriched(event.enriched));
        AtomicInteger sourceFinds = new AtomicInteger();
        AtomicInteger totalReads = new AtomicInteger();
        CommandListener listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent command) {
                if (!"find".equals(command.getCommandName())) { return; }
                String collection = command.getCommand().getString("find").getValue();
                if (List.of("source_lineage_enriched", "source_lineage_raw", "source_admission_receipts",
                        "source_admission_identities").contains(collection)) {
                    sourceFinds.incrementAndGet();
                    totalReads.incrementAndGet();
                }
            }
        };
        MongoClient measuredClient = MONGO.occurrenceReader(database, listener);
        List<Integer> batches = new ArrayList<>();
        MongoSourceLineage measuredSource = new MongoSourceLineage(measuredClient, database, "merchant_source_reader",
                MongoSourceLineage.Stage.SCORER, originVerifier, CLOCK);

        for (int count : List.of(1, 100, 1_000, 1_001)) {
            batches.clear();
            sourceFinds.set(0);
            List<SourceLineage> results = VerifiedMerchantAdmission.resolveSourceBindings(bindings -> {
                        batches.add(bindings.size());
                        return measuredSource.enrichedBindings(bindings);
                    },
                    java.util.Collections.nCopies(count, binding));
            int expectedBatches = (count + MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH - 1)
                    / MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH;
            assertThat(batches).hasSize(expectedBatches);
            assertThat(batches).allMatch(size -> size > 0 && size <= MongoSourceLineage.MAX_ENRICHED_BINDING_BATCH);
            assertThat(results).hasSize(count).allMatch(lineage -> lineage.state() == SourceLineage.State.VERIFIED);
            assertThat(sourceFinds.get()).isEqualTo(expectedBatches * 4);
        }
        assertThat(totalReads.get()).isEqualTo(4 * (1 + 1 + 1 + 2));
    }

    @Test
    void verifiedReadsHaveBoundedMerchantCoverageQueriesForOneHundredAndOneThousandDistinctRows() {
        Limits measuredLimits = new Limits(Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(31),
                Duration.ofDays(3), Duration.ofMinutes(2), 1_000, 2_000, 100, 5_000);
        VerifiedMerchantAdmission admission = adapter(grants, CLOCK, measuredLimits);
        Map<Integer, SourceEvent> targetEvents = new HashMap<>();
        Map<Integer, Staged> targetTickets = new HashMap<>();
        Map<Integer, PersistedScore> targetScores = new HashMap<>();
        Map<Integer, Snapshot> targetSnapshots = new HashMap<>();
        Map<Integer, Document> initialBindings = new HashMap<>();
        List<int[]> batches = new ArrayList<>(List.of(new int[] {1, 2}, new int[] {3, 102}));
        for (int first = 103; first <= 1_102; first += 100) {
            batches.add(new int[] {first, Math.min(first + 99, 1_102)});
        }

        long generation = 0;
        for (int[] batch : batches) {
            generation++;
            List<SourceEvent> events = new ArrayList<>();
            List<Staged> tickets = new ArrayList<>();
            for (int sequence = batch[0]; sequence <= batch[1]; sequence++) {
                SourceEvent event = sourceEvent(sequence, "merchant-a", performanceAge(sequence), 5_000);
                Staged staged = stageWithEvidence(admission, event, sequence);
                assertThat(staged.ticket.status()).as("staged sequence %d", sequence).isEqualTo(StageResult.CREATED);
                events.add(event);
                tickets.add(staged);
                if (List.of(2, 102, 1_102).contains(sequence)) {
                    targetEvents.put(sequence, event);
                    targetTickets.put(sequence, staged);
                }
            }

            Instant coverageStart = generation == 1 ? NOW.minus(Duration.ofHours(8))
                    : generation == 2 ? NOW.minus(Duration.ofMinutes(450)) : NOW.minus(Duration.ofMinutes(370));
            String previousCommitment = generation == 1 ? null : latestCoverageCommitment(events.getFirst());
            SourceEvent last = events.getLast();
            Signed seal = signedCoverage(generation, batch[0], batch[1], previousCommitment, coverageStart,
                    last.raw.createdAt(), grants.get(1), coverageKey);
            admission.seal(tickets.getLast().ticket, seal.proof, seal.payload);

        }

        // Keep the expensive O(n^2) commit setup out of this query-count test. Every source event,
        // observation assertion, receipt and coverage generation above is genuinely admitted and sealed;
        // the fixture materializes older committed history in one bulk operation. The three measured
        // target observations still use the normal commit path before being read through Mongo.
        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(
                sourceScope(targetEvents.get(1_102))));
        var candidates = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.CANDIDATES);
        var history = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.HISTORY);
        List<Document> seededHistory = new ArrayList<>();
        for (Document candidate : candidates.find(eq("scope", scopeId))) {
            long sequence = candidate.getLong("sequence");
            if (List.of(2L, 102L, 1_102L).contains(sequence)) { continue; }
            Observation observation = MerchantObservationDocumentCodec.decodeObservation(candidate);
            seededHistory.add(MerchantObservationDocumentCodec.observationDocument(observation)
                    .append("expireAt", java.util.Date.from(NOW.plus(measuredLimits.historyRetention()))));
        }
        if (!seededHistory.isEmpty()) { history.insertMany(seededHistory); }
        candidates.updateMany(new Document("scope", scopeId).append("status", "STAGED")
                        .append("sequence", new Document("$nin", List.of(2L, 102L, 1_102L))),
                new Document("$set", new Document("status", "COMMITTED")));
        ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .updateOne(eq("_id", scopeId), new Document("$set", new Document("pending", 3)));

        for (int sequence : List.of(2, 102, 1_102)) {
            CommitResult committed = admission.commit(targetTickets.get(sequence).ticket);
            assertThat(committed.status()).as("sequence %d", sequence).isEqualTo(CommitStatus.COMMITTED);
            int expectedPrior = sequence == 2 ? 1 : sequence == 102 ? 100 : 1_000;
            assertThat(committed.snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
            assertThat(committed.snapshot().priorCount()).isEqualTo(expectedPrior);
            targetSnapshots.put(sequence, committed.snapshot());
            PersistedScore published = publish(targetEvents.get(sequence), MongoSourceOccurrenceStore.INITIAL_RUN,
                    "merchant-query-score-" + sequence);
            targetScores.put(sequence, published);
            admission.bindScored(targetTickets.get(sequence).ticket, occurrenceReader, published.reference);
            initialBindings.put(sequence, manifest(targetEvents.get(sequence)).get("scoredBinding", Document.class));
        }

        QueryCounter merchantQueries = new QueryCounter();
        QueryCounter sourceQueries = new QueryCounter();
        MongoClient measuredLedgerClient = MONGO.merchantReader(database, merchantQueries);
        MongoClient measuredSourceClient = MONGO.occurrenceReader(database, sourceQueries);
        MongoSourceLineage measuredSource = new MongoSourceLineage(measuredSourceClient, database,
                "merchant_source_reader", MongoSourceLineage.Stage.SCORER, originVerifier, CLOCK);
        SourceEvent grantEvent = targetEvents.get(1_102);
        PrivateMerchantDiagnosticReader.ReadGrant grant = new PrivateMerchantDiagnosticReader.ReadGrant(
                "merchant_diagnostic_reader", PrivateMerchantDiagnosticReader.PURPOSE,
                PrivateMerchantDiagnosticReader.AUDIENCE, pseudonyms.scope(sourceScope(grantEvent)),
                pseudonyms.merchant("institution-a", "merchant-a"), measuredLimits.maxHistoryRows());
        PrivateMerchantDiagnosticReader measuredReader = new PrivateMerchantDiagnosticReader(measuredLedgerClient,
                database, grant, ignored -> true, measuredSource, occurrenceReader, origins, grants, pseudonyms,
                CLOCK, measuredLimits, () -> { }, System::nanoTime);

        List<QueryCounts> measurements = new ArrayList<>();
        for (int sequence : List.of(2, 102, 1_102)) {
            merchantQueries.reset();
            sourceQueries.reset();
            PrivateMerchantDiagnosticReader.Result result = measuredReader.evaluate(request(targetEvents.get(sequence),
                    targetTickets.get(sequence), targetScores.get(sequence).reference));
            assertThat(result.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.EVALUATED);
            assertThat(admission.commit(targetTickets.get(sequence).ticket).snapshot()).isEqualTo(targetSnapshots.get(sequence));
            assertThat(manifest(targetEvents.get(sequence)).get("scoredBinding", Document.class))
                    .isEqualTo(initialBindings.get(sequence));

            QueryCounts measured = new QueryCounts(sequence == 2 ? 1 : sequence == 102 ? 100 : 1_000,
                    merchantQueries.coverageGenerationFinds.get(), merchantQueries.merchantReads.get(),
                    sourceQueries.sourceLineageReads.get(), sourceQueries.sourceLineageFinds.get(),
                    merchantQueries.merchantGetMores.get(), sourceQueries.sourceLineageGetMores.get());
            measurements.add(measured);
            System.out.printf("FDP154-query-count prior=%d coverage-generation-finds=%d merchant-ledger-queries=%d "
                            + "source-lineage-queries=%d source-lineage-finds=%d merchant-getMore=%d source-lineage-getMore=%d%n",
                    measured.priorRows(), measured.coverageGenerationFinds(), measured.merchantLedgerQueries(),
                    measured.sourceLineageQueries(), measured.sourceLineageFinds(), measured.merchantGetMores(),
                    measured.sourceLineageGetMores());
        }

        assertThat(measurements).extracting(QueryCounts::coverageGenerationFinds).containsExactly(2, 2, 2);
        assertThat(measurements.stream().map(QueryCounts::merchantLedgerQueries).distinct()).hasSize(1);
        assertThat(measurements.stream().map(QueryCounts::sourceLineageQueries).distinct()).hasSize(1);
        assertThat(measurements.stream().map(QueryCounts::sourceLineageFinds).distinct()).hasSize(1);
    }

    @Test
    void stageBRejectsRescoresWrongReferencesAndBindingsConflictsWithoutOverwritingTheInitialFact() throws Exception {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent current = sourceEvent(1, "merchant-a", 60);
        var ticket = stage(admission, current, 1);
        Signed seal = coverage(1, current.raw.createdAt());
        admission.seal(ticket, seal.proof, seal.payload);
        admission.commit(ticket);

        PersistedScore initial = publish(current, MongoSourceOccurrenceStore.INITIAL_RUN, "score-initial-2");
        PersistedScore rescore = publish(current, "rescore-1", "score-rescore-2");
        SourceEvent unrelated = sourceEvent(2, "merchant-b", 30);
        PersistedScore unrelatedScore = publish(unrelated, MongoSourceOccurrenceStore.INITIAL_RUN, "score-unrelated-2");
        admission.bindScored(ticket, occurrenceReader, initial.reference);
        Document expected = manifest(current).get("scoredBinding", Document.class);

        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader, rescore.reference))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader,
                new SourceOccurrenceAccess.Reference("different-score-id", initial.reference.createdAt(), initial.reference.fingerprint())))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader,
                new SourceOccurrenceAccess.Reference(initial.reference.eventId(), initial.reference.createdAt().plusSeconds(1), initial.reference.fingerprint())))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader,
                new SourceOccurrenceAccess.Reference(initial.reference.eventId(), initial.reference.createdAt(), "0".repeat(64))))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader, unrelatedScore.reference))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
        assertThat(manifest(current).get("scoredBinding", Document.class)).isEqualTo(expected);

        // A second concurrent exact binder must reconcile to the same immutable document.
        VerifiedMerchantAdmission restarted = adapter();
        var restartedTicket = stage(restarted, current, 1);
        restarted.seal(restartedTicket, seal.proof, seal.payload);
        restarted.commit(restartedTicket);
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            var one = executor.submit(() -> { ready.countDown(); release.await(); admission.bindScored(ticket, occurrenceReader, initial.reference); return null; });
            var two = executor.submit(() -> { ready.countDown(); release.await(); restarted.bindScored(restartedTicket, occurrenceReader, initial.reference); return null; });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            one.get(20, TimeUnit.SECONDS);
            two.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(manifest(current).get("scoredBinding", Document.class)).isEqualTo(expected);

        Document conflict = new Document(expected).append("eventId", "conflicting-score-id");
        ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MANIFEST)
                .updateOne(eq("_id", manifest(current).getString("_id")), set("scoredBinding", conflict));
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader, initial.reference))
                .isInstanceOf(IllegalStateException.class).hasMessage("MERCHANT_STORE_SCORED_BINDING_CONFLICT");
    }

    @Test
    void privateReadReturnsOnlyACompleteWindowClaimForTheExactInitialOccurrence() {
        DiagnosticFixture fixture = diagnosticFixture(false);
        PrivateMerchantDiagnosticReader.Result result = fixture.reader.evaluate(fixture.request);

        assertThat(result.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.EVALUATED);
        assertThat(result.claim()).isEqualTo(PrivateMerchantDiagnosticReader.Claim.FIRST_OBSERVED_IN_COMPLETE_RECENT_WINDOW);
        assertThat(result.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.NONE);
        assertThat(result.toString()).doesNotContain("merchant-a", "institution-a", "transaction-1", "score-private-1");
        assertThat(fixture.request.toString()).doesNotContain("merchant-a", "institution-a", "transaction-1", "score-private-1");
        assertThat(PrivateMerchantDiagnosticReader.Result.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName).containsExactly("outcome", "claim", "reason");
    }

    @Test
    void privateReadDistinguishesPriorWindowEvidenceAndFailsClosedOnHistoryLoss() {
        DiagnosticFixture fixture = diagnosticFixture(true);
        assertThat(fixture.reader.evaluate(fixture.request).claim())
                .isEqualTo(PrivateMerchantDiagnosticReader.Claim.PREVIOUSLY_OBSERVED_IN_COMPLETE_RECENT_WINDOW);

        String priorId = manifest(fixture.prior).getString("_id");
        ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.HISTORY)
                .deleteOne(eq("_id", priorId));
        PrivateMerchantDiagnosticReader.Result afterLoss = fixture.reader.evaluate(fixture.request);
        assertThat(afterLoss.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(afterLoss.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.HISTORY_UNAVAILABLE);
        assertThat(afterLoss.claim()).isNull();
    }

    @Test
    void privateReadRequiresCurrentGrantReadOnlyPrincipalAndFinalAuthorityRechecks() {
        DiagnosticFixture fixture = diagnosticFixture(false);
        AtomicBoolean active = new AtomicBoolean(true);
        PrivateMerchantDiagnosticReader revokedDuringRead = reader(fixture.grant, active,
                () -> active.set(false), System::nanoTime);
        PrivateMerchantDiagnosticReader.Result denied = revokedDuringRead.evaluate(fixture.request);
        assertThat(denied.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.DENIED);
        assertThat(denied.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.READ_NOT_AUTHORIZED);

        AtomicBoolean coverageActive = new AtomicBoolean(true);
        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(fixture.current)));
        PrivateMerchantDiagnosticReader revokedCoverageDuringRead = reader(fixture.grant, coverageActive, () ->
                ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                        .updateOne(eq("_id", scopeId), set("coverageAnchor.proof", "AA=="),
                                new com.mongodb.client.model.UpdateOptions().bypassDocumentValidation(true)), System::nanoTime);
        PrivateMerchantDiagnosticReader.Result coverageTampered = revokedCoverageDuringRead.evaluate(fixture.request);
        assertThat(coverageTampered.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(coverageTampered.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.HISTORY_UNAVAILABLE);

    }

    @Test
    void privateReadRechecksTheSourceReceiptAfterTheSnapshotRead() {
        DiagnosticFixture fixture = diagnosticFixture(false);
        String receiptId = SourceReceiptIdentity.identity(sourceScope(fixture.current));
        PrivateMerchantDiagnosticReader lostReceiptDuringRead = reader(fixture.grant, new AtomicBoolean(true), () ->
                ownerClient.getDatabase(database).getCollection("source_admission_receipts")
                        .deleteOne(eq("_id", receiptId)), System::nanoTime);

        PrivateMerchantDiagnosticReader.Result unavailable = lostReceiptDuringRead.evaluate(fixture.request);
        assertThat(unavailable.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(unavailable.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.SOURCE_AUTHORITY_UNAVAILABLE);
    }

    @Test
    void privateReadScrubsInternalFailureAndNeverReturnsAClaim() {
        DiagnosticFixture fixture = diagnosticFixture(false);
        PrivateMerchantDiagnosticReader failedRead = reader(fixture.grant, new AtomicBoolean(true),
                () -> { throw new IllegalStateException("merchant-a transaction-1 score-private-1"); }, System::nanoTime);

        PrivateMerchantDiagnosticReader.Result result = failedRead.evaluate(fixture.request);

        assertThat(result.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.INTERNAL_UNAVAILABLE);
        assertThat(result.claim()).isNull();
        assertThat(result.toString()).doesNotContain("merchant-a", "transaction-1", "score-private-1");
    }

    @Test
    void signedSourceToKafkaInitialScoreToPrivateDiagnosticIsExactAndFailClosed() throws Exception {
        SourceEvent current = sourceEvent(1, "merchant-a", 60);
        VerifiedMerchantAdmission admission = adapter();
        Staged staged = stageWithEvidence(admission, current, 1);
        Signed seal = coverage(1, current.raw.createdAt());
        admission.seal(staged.ticket, seal.proof, seal.payload);
        assertThat(admission.commit(staged.ticket).snapshot().availability()).isEqualTo(Availability.COMPLETE_WINDOW);
        PrivateMerchantDiagnosticReader.ReadGrant grant = readGrant(current, "merchant-a");
        PrivateMerchantDiagnosticReader privateReader = reader(grant, new AtomicBoolean(true), () -> { }, System::nanoTime);

        try {
            KAFKA.start();
            scoredTopic = database + ".merchant-scored";
            PersistedScore published = publishThroughKafka(current, "score-private-e2e-1");
            admission.bindScored(staged.ticket, occurrenceReader, published.reference);
            PrivateMerchantDiagnosticReader.Request exactRequest = request(current, staged, published.reference);

            PrivateMerchantDiagnosticReader.Result positive = privateReader.evaluate(exactRequest);
            assertThat(positive.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.EVALUATED);
            assertThat(positive.claim()).isEqualTo(PrivateMerchantDiagnosticReader.Claim.FIRST_OBSERVED_IN_COMPLETE_RECENT_WINDOW);

            SourceOccurrenceAccess.Reference wrongFingerprint = new SourceOccurrenceAccess.Reference(
                    published.reference.eventId(), published.reference.createdAt(), "0".repeat(64));
            assertThatThrownBy(() -> admission.bindScored(staged.ticket, occurrenceReader, wrongFingerprint))
                    .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_VERIFIED_ADMISSION_DENIED");
            PrivateMerchantDiagnosticReader.Result negative = privateReader.evaluate(
                    request(current, staged, wrongFingerprint));
            assertThat(negative.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.PENDING);
            assertThat(negative.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.INITIAL_SCORED_PENDING);
            assertThat(negative.claim()).isNull();
        } finally {
            if (KAFKA.isRunning()) { KAFKA.stop(); }
        }
    }

    @Test
    void privateReadRejectsMerchantKeyExpiryBetweenSnapshotAndFinalRecheck() {
        DiagnosticFixture fixture = diagnosticFixture(false);
        MutableClock clock = new MutableClock(NOW);
        PrivateMerchantDiagnosticReader expiresDuringRead = reader(fixture.grant, new AtomicBoolean(true),
                () -> clock.set(NOW.plus(Duration.ofDays(1)).plusSeconds(1)), System::nanoTime, clock);
        PrivateMerchantDiagnosticReader.Result expired = expiresDuringRead.evaluate(fixture.request);
        assertThat(expired.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(expired.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.SOURCE_AUTHORITY_UNAVAILABLE);
    }

    @Test
    void privateReadDoesNotEvaluateLostBindingsWrongGrantsOrExpiredBudgets() {
        DiagnosticFixture fixture = diagnosticFixture(false);
        ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MANIFEST)
                .updateOne(eq("_id", manifest(fixture.current).getString("_id")), set("scoredBinding", null));
        PrivateMerchantDiagnosticReader.Result bindingPending = fixture.reader.evaluate(fixture.request);
        assertThat(bindingPending.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.PENDING);
        assertThat(bindingPending.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.STAGE_A_BINDING_PENDING);
        fixture.admission.bindScored(fixture.ticket, occurrenceReader, fixture.reference);

        PrivateMerchantDiagnosticReader.ReadGrant wrongMerchant = new PrivateMerchantDiagnosticReader.ReadGrant(
                fixture.grant.principal(), PrivateMerchantDiagnosticReader.PURPOSE, PrivateMerchantDiagnosticReader.AUDIENCE,
                fixture.grant.scope(), pseudonyms.merchant("institution-a", "merchant-other"), LIMITS.maxHistoryRows());
        PrivateMerchantDiagnosticReader.Result wrongMerchantResult = reader(wrongMerchant, new AtomicBoolean(true), () -> { }, System::nanoTime)
                .evaluate(fixture.request);
        assertThat(wrongMerchantResult.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.DENIED);
        assertThat(wrongMerchantResult.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.READ_NOT_AUTHORIZED);

        Scope scope = fixture.grant.scope();
        PrivateMerchantDiagnosticReader.ReadGrant wrongEpoch = new PrivateMerchantDiagnosticReader.ReadGrant(
                fixture.grant.principal(), PrivateMerchantDiagnosticReader.PURPOSE, PrivateMerchantDiagnosticReader.AUDIENCE,
                new Scope(scope.issuer(), scope.institution(), scope.source(), "e".repeat(64), scope.identityVersion()),
                fixture.grant.merchantPseudonym(), LIMITS.maxHistoryRows());
        assertThat(reader(wrongEpoch, new AtomicBoolean(true), () -> { }, System::nanoTime)
                .evaluate(fixture.request).outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.DENIED);

        PrivateMerchantDiagnosticReader.Request wrongFingerprint = request(fixture.current,
                new Staged(fixture.ticket, fixture.assertion), new SourceOccurrenceAccess.Reference(
                        fixture.reference.eventId(), fixture.reference.createdAt(), "0".repeat(64)));
        assertThat(fixture.reader.evaluate(wrongFingerprint).outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.PENDING);

        AtomicLong elapsed = new AtomicLong();
        PrivateMerchantDiagnosticReader timedOut = reader(fixture.grant, new AtomicBoolean(true), () -> { },
                () -> elapsed.getAndAdd(TimeUnit.SECONDS.toNanos(6)));
        PrivateMerchantDiagnosticReader.Result timeout = timedOut.evaluate(fixture.request);
        assertThat(timeout.outcome()).isEqualTo(PrivateMerchantDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(timeout.reason()).isEqualTo(PrivateMerchantDiagnosticReader.Reason.DEADLINE_EXCEEDED);
        assertThatThrownBy(() -> new PrivateMerchantDiagnosticReader(ownerClient, database, fixture.grant,
                ignored -> true, diagnosticSource, occurrenceReader, origins, grants, pseudonyms, CLOCK, LIMITS))
                .isInstanceOf(SecurityException.class).hasMessage("MERCHANT_PRIVATE_READ_NOT_AUTHORIZED");
        assertThatThrownBy(() -> merchantReaderClient.getDatabase(database)
                .getCollection(MongoMerchantObservationSchema.CANDIDATES).insertOne(new Document("_id", "unauthorized")))
                .isInstanceOf(RuntimeException.class);
    }

    private DiagnosticFixture diagnosticFixture(boolean withPrior) {
        VerifiedMerchantAdmission admission = adapter();
        SourceEvent prior = withPrior ? sourceEvent(1, "merchant-a", 180) : null;
        SourceEvent current = sourceEvent(withPrior ? 2 : 1, "merchant-a", 60);
        VerifiedMerchantAdmission.Ticket priorTicket = null;
        if (prior != null) { priorTicket = stage(admission, prior, 1); }
        Staged currentStaged = stageWithEvidence(admission, current, withPrior ? 2 : 1);
        Signed seal = coverage(withPrior ? 2 : 1, current.raw.createdAt());
        admission.seal(currentStaged.ticket, seal.proof, seal.payload);
        if (priorTicket != null) { admission.commit(priorTicket); }
        admission.commit(currentStaged.ticket);
        PersistedScore published = publish(current, MongoSourceOccurrenceStore.INITIAL_RUN,
                withPrior ? "score-private-prior-1" : "score-private-1");
        admission.bindScored(currentStaged.ticket, occurrenceReader, published.reference);
        PrivateMerchantDiagnosticReader.ReadGrant grant = readGrant(current, "merchant-a");
        PrivateMerchantDiagnosticReader reader = reader(grant, new AtomicBoolean(true), () -> { }, System::nanoTime);
        return new DiagnosticFixture(admission, prior, current, currentStaged.ticket, currentStaged.assertion,
                published.reference, grant, reader, request(current, currentStaged, published.reference));
    }

    private VerifiedMerchantAdmission adapter() {
        return new VerifiedMerchantAdmission(ownerClient, database, CLOCK, LIMITS, enricher, origins, grants, pseudonyms);
    }

    private VerifiedMerchantAdmission adapter(List<MerchantAssertionTrustPolicy.Grant> merchantGrants) {
        return new VerifiedMerchantAdmission(ownerClient, database, CLOCK, LIMITS, enricher, origins,
                merchantGrants, pseudonyms);
    }

    private VerifiedMerchantAdmission adapter(List<MerchantAssertionTrustPolicy.Grant> merchantGrants,
                                               Clock clock, Limits limits) {
        return new VerifiedMerchantAdmission(ownerClient, database, clock, limits, enricher, origins,
                merchantGrants, pseudonyms);
    }

    private List<MerchantAssertionTrustPolicy.Grant> rotatedCoverageGrants(boolean revokeFirst) {
        MerchantAssertionTrustPolicy.Grant observation = grants.getFirst();
        MerchantAssertionTrustPolicy.Grant first = coverageGrant(coverageKey, "merchant-coverage-issuer",
                "coverage-v1", coverageGrantId, revokeFirst);
        MerchantAssertionTrustPolicy.Grant second = coverageGrant(rotatedCoverageKey, "merchant-coverage-issuer-2",
                "coverage-v2", "merchant-coverage-grant-v2", false);
        MerchantAssertionTrustPolicy.Grant third = coverageGrant(thirdCoverageKey, "merchant-coverage-issuer-3",
                "coverage-v3", "merchant-coverage-grant-v3", false);
        return List.of(observation, first, second, third);
    }

    private MerchantAssertionTrustPolicy.Grant coverageGrant(KeyPair pair, String issuer, String version,
                                                              String grantId, boolean revoked) {
        return new MerchantAssertionTrustPolicy.Grant(MerchantAssertionVerifier.Role.COVERAGE, grantId, 1,
                trusted(issuer, version, MerchantAssertionVerifier.COVERAGE_PURPOSE,
                        MerchantAssertionTrustPolicy.AUDIENCE, pair, "merchant-policy-v1", revoked),
                identity, population, null);
    }

    private String verifiedCoverageCommitment(Signed signed, SourceEvent source,
                                               List<MerchantAssertionTrustPolicy.Grant> merchantGrants) {
        return verifiedCoverage(signed, source, merchantGrants, null, null).assertionCommitment();
    }

    private MerchantAssertionVerifier.VerifiedCoverage verifiedCoverage(Signed signed, SourceEvent source,
                                                                          List<MerchantAssertionTrustPolicy.Grant> merchantGrants,
                                                                          Signed previousSigned, SourceEvent previousSource) {
        MerchantAssertionVerifier verifier = new MerchantAssertionVerifier(origins, merchantGrants, CLOCK);
        MerchantAssertionVerifier.VerifiedCoverage previous = null;
        if (previousSigned != null) {
            MerchantAssertionVerifier.Result previousResult = verifier.verifyCoverage(previousSigned.proof,
                    previousSigned.payload, previousSource.origin, null);
            if (!(previousResult instanceof MerchantAssertionVerifier.VerifiedCoverage verifiedPrevious)) {
                throw new IllegalStateException("TEST_COVERAGE_SIGNATURE_INVALID");
            }
            previous = verifiedPrevious;
        }
        MerchantAssertionVerifier.Result result = verifier.verifyCoverage(signed.proof, signed.payload, source.origin, previous);
        if (!(result instanceof MerchantAssertionVerifier.VerifiedCoverage verified)) {
            throw new IllegalStateException("TEST_COVERAGE_SIGNATURE_INVALID");
        }
        return verified;
    }

    private Signed signedCoverage(long generation, long fromSequence, long throughSequence, String previousCommitment,
                                  Instant watermark, MerchantAssertionTrustPolicy.Grant grant, KeyPair signer) {
        return signedCoverage(generation, fromSequence, throughSequence, previousCommitment,
                NOW.minus(Duration.ofDays(1)), watermark, grant, signer);
    }

    private Signed signedCoverage(long generation, long fromSequence, long throughSequence, String previousCommitment,
                                  Instant coverageStart, Instant watermark,
                                  MerchantAssertionTrustPolicy.Grant grant, KeyPair signer) {
        String coverageId = "coverage-generation-" + generation;
        MerchantCoverageAssertion assertion = new MerchantCoverageAssertion(1, identity.issuer(), identity.institution(),
                identity.source(), identity.sourceEpoch(), grant.merchantGrantId(), grant.merchantGrantVersion(),
                grant.key().policyVersion(), identity.identityVersion(), coverageId, population.feedId(), population.id(),
                population.definitionVersion(), population.definitionCommitment(), population.inclusionRulesCommitment(),
                population.exclusionRulesCommitment(), 1, fromSequence, throughSequence, coverageStart,
                watermark, generation, previousCommitment);
        byte[] payload = MerchantAssertionCodec.encodeCoverage(assertion);
        byte[] proof = signed(grant.key(), signer, payload, coverageId, watermark);
        return new Signed(proof, payload);
    }

    private VerifiedMerchantAdmission.Ticket stage(VerifiedMerchantAdmission admission, SourceEvent event, long sequence) {
        return stageWithEvidence(admission, event, sequence).ticket;
    }

    private Staged stageWithEvidence(VerifiedMerchantAdmission admission, SourceEvent event, long sequence) {
        SourceScope source = sourceScope(event);
        String receipt = SourceReceiptIdentity.identity(event.origin.scope());
        String transaction = pseudonyms.transaction(source.institution(), source.source(), event.raw.transactionId());
        String occurrence = pseudonyms.occurrence(source.institution(), source.source(), event.raw.eventId(), receipt);
        String merchant = pseudonyms.merchant(source.institution(), event.raw.merchantInfo().merchantId());
        MerchantObservationAssertion assertion = new MerchantObservationAssertion(1, source.issuer(), source.institution(),
                source.source(), source.sourceEpoch(), observationGrantId, 1, "merchant-policy-v1", pseudonyms.identityVersion(),
                source.sourceEventId(), transaction, occurrence, receipt, SourceEventCommitment.raw(event.raw),
                SourceEventCommitment.enriched(event.enriched), merchant, pseudonyms.mappingVersion(), pseudonyms.mappingCommitment(),
                population.feedId(), population.id(), population.definitionVersion(), population.definitionCommitment(), sequence,
                source.eventTime(), source.eventTime());
        byte[] payload = MerchantAssertionCodec.encodeObservation(assertion);
        byte[] proof = signed(roleKey(MerchantAssertionVerifier.Role.OBSERVATION), observationKey, payload,
                source.sourceEventId(), source.eventTime());
        var ticket = admission.stage(event.sourceProof, event.sourcePayload, proof, payload, event.raw, event.enriched);
        return new Staged(ticket, new Signed(proof, payload));
    }

    private PrivateMerchantDiagnosticReader reader(PrivateMerchantDiagnosticReader.ReadGrant grant,
                                                    AtomicBoolean active, Runnable afterSnapshotRead,
                                                    java.util.function.LongSupplier nanoTime) {
        return reader(grant, active, afterSnapshotRead, nanoTime, CLOCK);
    }

    private PrivateMerchantDiagnosticReader reader(PrivateMerchantDiagnosticReader.ReadGrant grant,
                                                    AtomicBoolean active, Runnable afterSnapshotRead,
                                                    java.util.function.LongSupplier nanoTime, Clock clock) {
        return reader(grant, active, afterSnapshotRead, nanoTime, clock, grants);
    }

    private PrivateMerchantDiagnosticReader reader(PrivateMerchantDiagnosticReader.ReadGrant grant,
                                                    AtomicBoolean active, Runnable afterSnapshotRead,
                                                    java.util.function.LongSupplier nanoTime, Clock clock,
                                                    List<MerchantAssertionTrustPolicy.Grant> merchantGrants) {
        return new PrivateMerchantDiagnosticReader(merchantReaderClient, database, grant, ignored -> active.get(),
                diagnosticSource, occurrenceReader, origins, merchantGrants, pseudonyms, clock, LIMITS,
                afterSnapshotRead, nanoTime);
    }

    private PrivateMerchantDiagnosticReader.ReadGrant readGrant(SourceEvent event, String merchantId) {
        return new PrivateMerchantDiagnosticReader.ReadGrant("merchant_diagnostic_reader",
                PrivateMerchantDiagnosticReader.PURPOSE, PrivateMerchantDiagnosticReader.AUDIENCE,
                pseudonyms.scope(sourceScope(event)), pseudonyms.merchant("institution-a", merchantId), LIMITS.maxHistoryRows());
    }

    private static PrivateMerchantDiagnosticReader.Request request(SourceEvent event, Staged staged,
                                                                   SourceOccurrenceAccess.Reference reference) {
        return new PrivateMerchantDiagnosticReader.Request(event.sourceProof, event.sourcePayload,
                staged.assertion.proof, staged.assertion.payload, event.raw, event.enriched, reference);
    }

    private Signed coverage(long throughSequence, Instant watermark) {
        MerchantCoverageAssertion assertion = new MerchantCoverageAssertion(1, identity.issuer(), identity.institution(),
                identity.source(), identity.sourceEpoch(), coverageGrantId, 1, "merchant-policy-v1", identity.identityVersion(),
                "coverage-generation-1", population.feedId(), population.id(), population.definitionVersion(),
                population.definitionCommitment(), population.inclusionRulesCommitment(), population.exclusionRulesCommitment(),
                1, 1, throughSequence, NOW.minus(Duration.ofDays(1)), watermark, 1, null);
        byte[] payload = MerchantAssertionCodec.encodeCoverage(assertion);
        byte[] proof = signed(roleKey(MerchantAssertionVerifier.Role.COVERAGE), coverageKey, payload,
                assertion.coverageId(), watermark);
        return new Signed(proof, payload);
    }

    private SourceEvent sourceEvent(int sequence, String merchantId, long secondsAgo) {
        return sourceEvent(sequence, merchantId, secondsAgo, 1_000);
    }

    private SourceEvent sourceEvent(int sequence, String merchantId, long secondsAgo, int maximumReceipts) {
        Instant eventTime = NOW.minusSeconds(secondsAgo);
        TransactionRawEvent raw = new TransactionRawEvent("raw-event-" + sequence, "transaction-" + sequence,
                "correlation-" + sequence, "customer-" + sequence, "account-1", "instrument-1", eventTime,
                eventTime, null, new MerchantInfo(merchantId, "Test merchant", "5411", "Grocery", "NL",
                "CARD_PRESENT", true, Map.of()), null, null, null, "PURCHASE", "CHIP", "BANK", "trace-" + sequence, Map.of());
        byte[] payload = JSON.writeValueAsBytes(raw);
        SourceScope scope = new SourceScope(originKey.issuer(), originKey.institution(), originKey.source(), originKey.sourceEpoch(),
                originKey.signingKeyVersion(), raw.eventId(), raw.createdAt(), originKey.purpose(), originKey.audience(),
                SourceSignedInput.payloadCommitment(payload));
        byte[] proof = signed(originKey, sourceKey, payload, raw.eventId(), raw.createdAt());
        SourceSignatureVerifier.Result result = originVerifier.verify(proof, payload,
                new SourceSignatureVerifier.Context(originKey.purpose(), originKey.audience()));
        if (!(result instanceof SourceSignatureVerifier.Verified verified)) { throw new IllegalStateException("TEST_SOURCE_SIGNATURE_INVALID"); }
        DeviceTestSourceReceipts.admit(ownerClient, database, CLOCK, originVerifier, owner, verified, raw, maximumReceipts);

        Map<String, Object> featureSnapshot = new LinkedHashMap<>();
        FraudFeatureContract.JAVA_ENRICHED_FEATURE_NAMES.forEach(name -> featureSnapshot.put(name, 0.0));
        TransactionEnrichedEvent enriched = new TransactionEnrichedEventMapper().toAttestedEvent(raw,
                new EnrichedTransactionFeatures(featureSnapshot));
        SourceLineage enrichedLineage = enricher.bindEnriched(raw, enriched);
        if (enrichedLineage.state() != SourceLineage.State.VERIFIED) { throw new IllegalStateException("TEST_ENRICHED_LINEAGE_INVALID"); }
        return new SourceEvent(raw, enriched, proof, payload, verified);
    }

    private String latestCoverageCommitment(SourceEvent event) {
        String scopeId = MerchantObservationDocumentCodec.scopeId(pseudonyms.scope(sourceScope(event)));
        Document source = ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.COVERAGE)
                .find(eq("_id", scopeId)).first();
        return source.get("coverageAnchor", Document.class).getString("commitment");
    }

    private static long performanceAge(int sequence) {
        if (sequence == 1) { return Duration.ofHours(6).plusMinutes(20).toSeconds(); }
        if (sequence == 2) { return Duration.ofHours(6).plusMinutes(8).toSeconds(); }
        if (sequence <= 101) {
            return Duration.ofHours(6).plusMinutes(7).toSeconds()
                    - ((sequence - 3L) * Duration.ofMinutes(56).toSeconds() / 98);
        }
        return Duration.ofHours(5).plusMinutes(9).toSeconds()
                - ((sequence - 102L) * Duration.ofMinutes(59).toSeconds() / 1_000);
    }

    private SourceScope sourceScope(SourceEvent event) { return event.origin.scope(); }

    private Document manifest(SourceEvent event) {
        String id = MerchantObservationDocumentCodec.eventId(pseudonyms.scope(sourceScope(event)),
                pseudonyms.sourceEvent("institution-a", "BANK", event.raw.eventId()));
        return ownerClient.getDatabase(database).getCollection(MongoMerchantObservationSchema.MANIFEST)
                .find(eq("_id", id)).first();
    }

    private PersistedScore publish(SourceEvent source, String runId, String scoreId) {
        TransactionScoredEvent event = score(source, scoreId);
        var pending = occurrenceWriter.prepare(source.enriched, runId, event);
        assertThat(pending.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PENDING);
        var claim = occurrenceWriter.claim(pending.id());
        assertThat(claim.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHING);
        // The focused P04 tests exercise persisted acknowledgement and source lookup; P07 covers Kafka transport.
        var published = occurrenceWriter.confirm(pending.id(), claim.event(), claim.claimId());
        assertThat(published.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHED);
        return new PersistedScore(event, reference(event));
    }

    private PersistedScore publishThroughKafka(SourceEvent source, String scoreId) throws Exception {
        TransactionScoredEvent candidate = score(source, scoreId);
        var pending = occurrenceWriter.prepare(source.enriched, MongoSourceOccurrenceStore.INITIAL_RUN, candidate);
        assertThat(pending.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PENDING);
        var claim = occurrenceWriter.claim(pending.id());
        assertThat(claim.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHING);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(scoredTopic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        byte[] bytes = new JacksonKafkaSerializer<TransactionScoredEvent>().serialize(scoredTopic, claim.event());
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(scoredTopic, claim.event().eventId(), bytes)).get(15, TimeUnit.SECONDS);
        }
        TransactionScoredEvent consumed = consumeScored(scoreId);
        var persisted = occurrenceWriter.confirm(pending.id(), consumed, claim.claimId());
        assertThat(persisted.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHED);
        assertThat(persisted.event()).isEqualTo(consumed);
        return new PersistedScore(consumed, reference(consumed));
    }

    private TransactionScoredEvent consumeScored(String expectedId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "merchant-private-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(scoredTopic));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < until) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (expectedId.equals(record.key())) {
                        return new JacksonKafkaDeserializer<>(TransactionScoredEvent.class)
                                .deserialize(scoredTopic, record.value());
                    }
                }
            }
        }
        throw new AssertionError("MERCHANT_SCORED_KAFKA_RECORD_NOT_RECEIVED");
    }

    private static SourceOccurrenceAccess.Reference reference(TransactionScoredEvent event) {
        return new SourceOccurrenceAccess.Reference(event.eventId(), event.createdAt(), SourceScoredOccurrence.fingerprint(event));
    }

    private static TransactionScoredEvent score(SourceEvent source, String id) {
        TransactionEnrichedEvent event = source.enriched;
        return new TransactionScoredEvent(id, event.transactionId(), event.correlationId(), event.customerId(), event.accountId(),
                NOW, event.transactionTimestamp(), event.transactionAmount(), event.merchantInfo(), event.deviceInfo(),
                event.locationInfo(), event.customerContext(), 0.12d, RiskLevel.LOW, "RULES_ONLY", null, null,
                NOW, List.of(), Map.of("source", "rules"), event.featureSnapshot(), false, List.of(), null, null,
                MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED, null);
    }

    private SourceVerificationProperties.TrustedKey roleKey(MerchantAssertionVerifier.Role role) {
        return grants.stream().filter(grant -> grant.role() == role).findFirst().orElseThrow().key();
    }

    private static SourceVerificationProperties.TrustedKey trusted(String issuer, String version, String purpose,
                                                                    String audience, KeyPair pair, String policyVersion) {
        return trusted(issuer, version, purpose, audience, pair, policyVersion, false);
    }

    private static SourceVerificationProperties.TrustedKey trusted(String issuer, String version, String purpose,
                                                                    String audience, KeyPair pair, String policyVersion,
                                                                    boolean revoked) {
        return new SourceVerificationProperties.TrustedKey(issuer, "institution-a", "BANK", "epoch-a", version,
                "Ed25519", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                NOW.minus(Duration.ofDays(60)), NOW.plus(Duration.ofDays(1)), revoked, purpose, audience, policyVersion);
    }

    private static byte[] signed(SourceVerificationProperties.TrustedKey key, KeyPair pair, byte[] payload,
                                 String eventId, Instant eventTime) {
        SourceScope scope = new SourceScope(key.issuer(), key.institution(), key.source(), key.sourceEpoch(),
                key.signingKeyVersion(), eventId, eventTime, key.purpose(), key.audience(), SourceSignedInput.payloadCommitment(payload));
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(pair.getPrivate());
            signature.update(SourceSignedInput.encode(scope, payload));
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("protocolVersion", 1); proof.put("algorithm", "Ed25519"); proof.put("issuer", scope.issuer());
            proof.put("institution", scope.institution()); proof.put("source", scope.source()); proof.put("sourceEpoch", scope.sourceEpoch());
            proof.put("signingKeyVersion", scope.signingKeyVersion()); proof.put("sourceEventId", scope.sourceEventId());
            proof.put("eventTime", scope.eventTime().toString()); proof.put("purpose", scope.purpose()); proof.put("audience", scope.audience());
            proof.put("payloadCommitment", scope.payloadCommitment());
            proof.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
            return JSON.writeValueAsBytes(proof);
        } catch (Exception unavailable) { throw new IllegalStateException("TEST_SIGNATURE_FAILED"); }
    }

    private static KeyPair key() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static byte[] randomKey() { byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes); return bytes; }

    private record SourceEvent(TransactionRawEvent raw, TransactionEnrichedEvent enriched, byte[] sourceProof,
                               byte[] sourcePayload, SourceSignatureVerifier.Verified origin) { }
    private record Signed(byte[] proof, byte[] payload) { }
    private record Staged(VerifiedMerchantAdmission.Ticket ticket, Signed assertion) { }
    private record PersistedScore(TransactionScoredEvent event, SourceOccurrenceAccess.Reference reference) { }
    private record QueryCounts(int priorRows, int coverageGenerationFinds, int merchantLedgerQueries,
                               int sourceLineageQueries, int sourceLineageFinds,
                               int merchantGetMores, int sourceLineageGetMores) { }
    private record DiagnosticFixture(VerifiedMerchantAdmission admission, SourceEvent prior, SourceEvent current,
                                    VerifiedMerchantAdmission.Ticket ticket, Signed assertion,
                                    SourceOccurrenceAccess.Reference reference,
                                    PrivateMerchantDiagnosticReader.ReadGrant grant,
                                    PrivateMerchantDiagnosticReader reader,
                                    PrivateMerchantDiagnosticReader.Request request) { }

    private static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> current;
        private MutableClock(Instant initial) { current = new java.util.concurrent.atomic.AtomicReference<>(initial); }
        void set(Instant instant) { current.set(instant); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }

    private static final class QueryCounter implements CommandListener {
        private static final List<String> SOURCE_COLLECTIONS = List.of("source_lineage_enriched", "source_lineage_raw",
                "source_admission_receipts", "source_admission_identities");
        private final AtomicInteger coverageGenerationFinds = new AtomicInteger();
        private final AtomicInteger merchantReads = new AtomicInteger();
        private final AtomicInteger sourceLineageReads = new AtomicInteger();
        private final AtomicInteger sourceLineageFinds = new AtomicInteger();
        private final AtomicInteger merchantGetMores = new AtomicInteger();
        private final AtomicInteger sourceLineageGetMores = new AtomicInteger();

        @Override public void commandStarted(CommandStartedEvent event) {
            String name = event.getCommandName();
            var command = event.getCommand();
            if ("getMore".equals(name)) {
                BsonValue collectionValue = command.get("collection");
                if (collectionValue == null || !collectionValue.isString()) { return; }
                String collection = collectionValue.asString().getValue();
                if (collection.startsWith("merchant_")) { merchantGetMores.incrementAndGet(); }
                if (SOURCE_COLLECTIONS.contains(collection)) { sourceLineageGetMores.incrementAndGet(); }
                return;
            }
            if (!List.of("find", "aggregate", "count", "distinct").contains(name)) { return; }
            BsonValue collectionValue = command.get(name);
            if (collectionValue == null || !collectionValue.isString()) { return; }
            String collection = collectionValue.asString().getValue();
            if (collection.startsWith("merchant_")) { merchantReads.incrementAndGet(); }
            if (SOURCE_COLLECTIONS.contains(collection)) {
                sourceLineageReads.incrementAndGet();
                if ("find".equals(name)) { sourceLineageFinds.incrementAndGet(); }
            }
            if ("find".equals(name) && MongoMerchantObservationSchema.MEMBERSHIP_TIPS.equals(collection)) {
                BsonValue filterValue = command.get("filter");
                if (containsStringEquality(filterValue, "kind",
                        MerchantObservationDocumentCodec.COVERAGE_GENERATION_KIND)) {
                    coverageGenerationFinds.incrementAndGet();
                }
            }
        }

        private static boolean containsStringEquality(BsonValue value, String field, String expected) {
            if (value == null) { return false; }
            if (value.isDocument()) {
                var document = value.asDocument();
                BsonValue direct = document.get(field);
                if (direct != null && direct.isString() && expected.equals(direct.asString().getValue())) {
                    return true;
                }
                return document.values().stream().anyMatch(child -> containsStringEquality(child, field, expected));
            }
            if (value.isArray()) {
                return value.asArray().stream().anyMatch(child -> containsStringEquality(child, field, expected));
            }
            return false;
        }

        void reset() {
            coverageGenerationFinds.set(0);
            merchantReads.set(0);
            sourceLineageReads.set(0);
            sourceLineageFinds.set(0);
            merchantGetMores.set(0);
            sourceLineageGetMores.set(0);
        }
    }

    private static final class TestMongo implements AutoCloseable {
        private final GenericContainer<?> server = new GenericContainer<>(DockerImageName.parse("mongo:7.0@sha256:41e8195b2791692695e1dc9bd21cd51d0a3af05fff8ace9304a5c1acf94f1ae1"))
                .withExposedPorts(27017).withStartupTimeout(Duration.ofSeconds(60))
                .withCommand("bash", "-c", "openssl rand -base64 756 > /tmp/lineage-key; chmod 400 /tmp/lineage-key; "
                        + "exec mongod --replSet merchant-rs --bind_ip_all --keyFile /tmp/lineage-key --setParameter enableTestCommands=1");
        private final List<MongoClient> clients = new ArrayList<>();

        void start() throws Exception {
            server.start();
            execute("rs.initiate({_id:'merchant-rs',members:[{_id:0,host:'localhost:27017'}]}); "
                    + "let n=0; while(!db.hello().isWritablePrimary && n++<100){sleep(100)}; "
                    + "if(!db.hello().isWritablePrimary){throw Error('primary unavailable')}; "
                    + "db.getSiblingDB('admin').createUser({user:'source_owner',pwd:'ephemeral-owner-only',roles:['root']})");
        }

        MongoClient owner() { return connect("source_owner", "ephemeral-owner-only", "admin"); }

        MongoClient enricher(MongoClient owner, String database) {
            List<Document> privileges = new ArrayList<>();
            privileges.add(privilege(database, "", List.of("listCollections")));
            for (String collection : List.of("source_admission_identities", "source_admission_receipts",
                    "source_lineage_raw", "source_lineage_enriched")) {
                privileges.add(privilege(database, collection, List.of("find")));
            }
            for (String collection : List.of("source_lineage_raw", "source_lineage_enriched",
                    "source_lineage_raw_counts", "source_lineage_enriched_counts")) {
                privileges.add(privilege(database, collection, List.of("listIndexes")));
            }
            privileges.add(privilege(database, "source_lineage_enriched", List.of("insert", "update")));
            privileges.add(privilege(database, "source_lineage_enriched_counts", List.of("find", "insert", "update")));
            owner.getDatabase(database).runCommand(new Document("createRole", "merchant_source_enricher")
                    .append("privileges", privileges).append("roles", List.of()));
            owner.getDatabase(database).runCommand(new Document("createUser", "enricher")
                    .append("pwd", "ephemeral-enricher-only").append("roles", List.of("merchant_source_enricher")));
            return connect("enricher", "ephemeral-enricher-only", database);
        }

        MongoClient occurrenceWriter(MongoClient owner, String database) {
            List<Document> privileges = new ArrayList<>();
            privileges.add(privilege(database, "", List.of("listCollections")));
            for (String collection : List.of("source_admission_identities", "source_admission_receipts",
                    "source_lineage_raw", "source_lineage_enriched")) {
                privileges.add(privilege(database, collection, List.of("find")));
            }
            for (String collection : List.of("source_lineage_raw", "source_lineage_enriched",
                    "source_lineage_raw_counts", "source_lineage_enriched_counts")) {
                privileges.add(privilege(database, collection, List.of("listIndexes")));
            }
            for (String collection : List.of("source_scored_occurrences", "source_scored_occurrence_counts")) {
                privileges.add(privilege(database, collection, List.of("find", "insert", "update", "listIndexes")));
            }
            owner.getDatabase(database).runCommand(new Document("createRole", "merchant_scored_writer")
                    .append("privileges", privileges).append("roles", List.of()));
            owner.getDatabase(database).runCommand(new Document("createUser", "merchant_scored_writer")
                    .append("pwd", "ephemeral-scored-only").append("roles", List.of("merchant_scored_writer")));
            return connect("merchant_scored_writer", "ephemeral-scored-only", database);
        }

        MongoClient occurrenceReader(MongoClient owner, String database) {
            List<Document> privileges = new ArrayList<>();
            privileges.add(privilege(database, "", List.of("listCollections")));
            for (String collection : List.of("source_admission_identities", "source_admission_receipts",
                    "source_lineage_raw", "source_lineage_enriched", "source_scored_occurrences")) {
                privileges.add(privilege(database, collection, List.of("find")));
            }
            for (String collection : List.of("source_lineage_raw", "source_lineage_enriched",
                    "source_lineage_raw_counts", "source_lineage_enriched_counts",
                    "source_scored_occurrences", "source_scored_occurrence_counts")) {
                privileges.add(privilege(database, collection, List.of("listIndexes")));
            }
            owner.getDatabase(database).runCommand(new Document("createRole", "merchant_source_reader")
                    .append("privileges", privileges).append("roles", List.of()));
            owner.getDatabase(database).runCommand(new Document("createUser", "merchant_source_reader")
                    .append("pwd", "ephemeral-source-read-only").append("roles", List.of("merchant_source_reader")));
            return connect("merchant_source_reader", "ephemeral-source-read-only", database);
        }

        MongoClient occurrenceReader(String database, CommandListener listener) {
            return connect("merchant_source_reader", "ephemeral-source-read-only", database, listener);
        }

        MongoClient merchantReader(String database, CommandListener listener) {
            return connect("merchant_diagnostic_reader", "ephemeral-merchant-read-only", database, listener);
        }

        MongoClient merchantReader(MongoClient owner, String database) {
            List<Document> privileges = new ArrayList<>();
            privileges.add(privilege(database, "", List.of("listCollections")));
            for (String collection : MongoMerchantObservationSchema.COLLECTIONS) {
                privileges.add(privilege(database, collection, List.of("find", "listIndexes")));
            }
            owner.getDatabase(database).runCommand(new Document("createRole", "merchant_diagnostic_reader")
                    .append("privileges", privileges).append("roles", List.of()));
            owner.getDatabase(database).runCommand(new Document("createUser", "merchant_diagnostic_reader")
                    .append("pwd", "ephemeral-merchant-read-only").append("roles", List.of("merchant_diagnostic_reader")));
            return connect("merchant_diagnostic_reader", "ephemeral-merchant-read-only", database);
        }

        private MongoClient connect(String user, String password, String authDatabase) {
            return connect(user, password, authDatabase, null);
        }

        private MongoClient connect(String user, String password, String authDatabase, CommandListener listener) {
            String address = server.getHost() + ":" + server.getMappedPort(27017);
            String uri = "mongodb://" + user + ":" + password + "@" + address + "/?directConnection=true&authSource=" + authDatabase;
            MongoClientSettings.Builder settings = MongoClientSettings.builder().applyConnectionString(
                    new com.mongodb.ConnectionString(uri)).timeout(5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (listener != null) { settings.addCommandListener(listener); }
            MongoClient client = MongoClients.create(settings.build());
            clients.add(client);
            return client;
        }

        private static Document privilege(String database, String collection, List<String> actions) {
            return new Document("resource", new Document("db", database).append("collection", collection))
                    .append("actions", actions);
        }

        private void execute(String script) throws Exception {
            var result = server.execInContainer("mongosh", "--quiet", "--eval", script);
            if (result.getExitCode() != 0) { throw new IllegalStateException("TEST_MONGO_BOOTSTRAP_FAILED: " + result.getStderr()); }
        }

        @Override public void close() { clients.forEach(MongoClient::close); server.stop(); }
    }
}
