package com.frauddetection.enricher.device;

import com.mongodb.ExplainVerbosity;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.source.*;
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
import org.bson.conversions.Bson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.*;

/** Private diagnostic proof: real signed source, authenticated Mongo, published SCORED bytes and exact binding. */
@Tag("UNTRUSTED_TEST_ONLY")
class PrivateDeviceDiagnosticReaderIntegrationTest {
    private static final int PERFORMANCE_WARMUPS = 2;
    private static final int PERFORMANCE_SAMPLES = 20;
    private static final Duration PERFORMANCE_FIXTURE_WRITER_TIMEOUT = Duration.ofSeconds(30);
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
    private VerifiedDeviceAdmissionIntegrationTest f;
    private MongoSourceOccurrenceStore occurrenceWriter;
    private SourceOccurrenceAccess occurrenceReader;
    private MongoSourceLineage privateSource;
    private MongoClientHandle clients;
    private PrivateDeviceDiagnosticReader diagnostic;
    private String topic;
    private boolean topicCreated;

    @BeforeAll static void start() throws Exception {
        VerifiedDeviceAdmissionIntegrationTest.MONGO.start();
        KAFKA.start();
    }

    @AfterAll static void stop() {
        KAFKA.stop();
        VerifiedDeviceAdmissionIntegrationTest.MONGO.close();
    }

    @BeforeEach void setUp() {
        f = new VerifiedDeviceAdmissionIntegrationTest();
        f.setUp();
        topic = f.database + ".scored";
        clients = new MongoClientHandle(VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceWriter(f.ownerClient, f.database),
                VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceReader(f.ownerClient, f.database),
                VerifiedDeviceAdmissionIntegrationTest.MONGO.diagnosticReader(f.ownerClient, f.database));
        SourceOccurrenceCipher cipher = new SourceOccurrenceCipher("test-key", Map.of("test-key", Base64.getEncoder().encodeToString(VerifiedDeviceAdmissionIntegrationTest.randomKey())));
        occurrenceWriter = new MongoSourceOccurrenceStore(clients.writer, f.database, "scored_writer", f.originVerifier,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK, cipher, Duration.ofHours(1));
        occurrenceReader = MongoSourceOccurrenceStore.readOnly(clients.sourceReader, f.database, "source_diagnostic_reader", f.originVerifier,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK, cipher,
                new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest"));
        privateSource = new MongoSourceLineage(clients.sourceReader, f.database, "source_diagnostic_reader", MongoSourceLineage.Stage.SCORER,
                f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        var readGrant = new PrivateDeviceDiagnosticReader.ReadGrant("device_reader", PrivateDeviceDiagnosticReader.PURPOSE,
                PrivateDeviceDiagnosticReader.AUDIENCE, new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "transaction-ingest", f.pseudonyms.customer(f.A.institution, "customer-1"), 1);
        diagnostic = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database, readGrant, privateSource, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
    }

    @AfterEach void cleanUp() {
        if (f != null) { f.cleanup(); }
        if (clients != null) { clients.close(); }
    }

    @Test void occurrenceReaderRejectsUnneededFindPrivileges() {
        var overprivileged = VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceReader(f.ownerClient, f.database,
                "source_diagnostic_reader_overprivileged", List.of(new org.bson.Document("resource",
                        new org.bson.Document("db", f.database).append("collection", "source_enriched_publications"))
                        .append("actions", List.of("find"))));
        try {
            assertThatThrownBy(() -> MongoSourceOccurrenceStore.readOnly(overprivileged, f.database,
                    "source_diagnostic_reader_overprivileged", f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK,
                    sourceCipher(), new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("SOURCE_OCCURRENCE_READ_ONLY_PRINCIPAL_REQUIRED");
        } finally {
            overprivileged.close();
        }
    }

    @Test void stageBExplainsAllMatchingCandidatesWhileStageAFiltersToCommittedLiveCandidates() {
        Instant asOf = VerifiedDeviceAdmissionIntegrationTest.NOW;
        Instant windowStart = asOf.minus(Duration.ofDays(30));
        Bson stageB = DeviceHistoryQueries.membership(f.A.epoch(), "institution-token", "BANK", 1, windowStart, asOf,
                "customer-token", "device-token");
        Bson stageA = DeviceHistoryQueries.committedStageAMembership(f.A.epoch(), "institution-token", "BANK", 1,
                windowStart, asOf, "customer-token", "device-token", asOf);
        String stageBShape = stageB.toBsonDocument(org.bson.Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson();
        String stageAShape = stageA.toBsonDocument(org.bson.Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson();

        assertThat(stageBShape).contains("customer", "device", "keyVersion", "eventTime", "sourceEpoch")
                .doesNotContain("status", "terminalExpireAt");
        assertThat(stageAShape).contains("status", "COMMITTED", "terminalExpireAt");
    }

    @Test void validationBudgetFailsClosedAtSourceHistorySnapshotRebindAndMongoCheckpoints() throws Exception {
        Instant priorTime = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(3_600);
        var prior = f.event(f.A, "budget-prior", "budget-prior-transaction", "customer-1", "budget-device-prior", 1, priorTime);
        var admission = f.adapter(f.A);
        var priorTicket = f.stage(admission, prior);
        var priorSeal = f.coverage(f.A, 1, f.A.start, priorTime);
        admission.seal(priorTicket, priorSeal.proof(), priorSeal.payload());
        admission.commit(priorTicket);
        var priorScore = publish(prior, "initial", "budget-score-prior");
        admission.bindScored(priorTicket, occurrenceReader, priorScore.reference);

        var current = f.event(f.A, "budget-current", "budget-current-transaction", "customer-1", "budget-device-current", 2,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var currentTicket = f.stage(admission, current);
        var currentSeal = f.coverage(f.A, 2, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(currentTicket, currentSeal.proof(), currentSeal.payload());
        admission.commit(currentTicket);
        var currentScore = publish(current, "initial", "budget-score-current");
        admission.bindScored(currentTicket, occurrenceReader, currentScore.reference);

        long overBudget = TimeUnit.SECONDS.toNanos(5) + 1;
        for (String checkpoint : List.of("initial-source", "history-grant", "between-snapshots", "final-rebind", "mongo-read")) {
            java.util.concurrent.atomic.AtomicLong logicalTime = new java.util.concurrent.atomic.AtomicLong();
            Runnable expire = () -> logicalTime.addAndGet(overBudget);
            var reader = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database,
                    readGrant(f.pseudonyms.customer(f.A.institution, "customer-1")), privateSource, occurrenceReader,
                    new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                    VerifiedDeviceAdmissionIntegrationTest.CLOCK,
                    checkpoint.equals("between-snapshots") ? expire : () -> { },
                    checkpoint.equals("history-grant") ? expire : () -> { }, () -> { },
                    checkpoint.equals("initial-source") ? expire : () -> { },
                    checkpoint.equals("mongo-read") ? expire : () -> { },
                    checkpoint.equals("final-rebind") ? expire : () -> { }, logicalTime::get);

            assertThat(reader.evaluate(fact(current), currentScore.reference).outcome()).as(checkpoint)
                    .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        }

        var delayedReader = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database,
                readGrant(f.pseudonyms.customer(f.A.institution, "customer-1")), privateSource, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK, () -> { }, () -> { }, () -> { }, () -> {
                    try { Thread.sleep(5_050); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                }, () -> { }, () -> { }, System::nanoTime);
        long wallStarted = System.nanoTime();
        assertThat(delayedReader.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallStarted)).isGreaterThanOrEqualTo(5_000);
    }

    @Test void publishedScoredFactAuthorizesOnlyExactCompletePreEventSnapshotAndBoundedPredicates() throws Exception {
        Instant priorTime = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(3_600);
        var prior = f.event(f.A, "source-before", "transaction-before", "customer-1", "device-old", 1, priorTime);
        var admission = f.adapter(f.A);
        var priorTicket = f.stage(admission, prior);
        var priorSeal = f.coverage(f.A, 1, f.A.start, priorTime);
        admission.seal(priorTicket, priorSeal.proof(), priorSeal.payload());
        admission.commit(priorTicket);
        var priorPublished = publish(prior, "initial", "score-before");
        admission.bindScored(priorTicket, occurrenceReader, priorPublished.reference);

        var current = f.event(f.A, "source-current", "transaction-current", "customer-1", "device-new", 2,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var currentTicket = f.stage(admission, current);
        var currentSeal = f.coverage(f.A, 2, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(currentTicket, currentSeal.proof(), currentSeal.payload());
        admission.commit(currentTicket);
        var scored = publish(current, "initial", "score-current");
        admission.bindScored(currentTicket, occurrenceReader, scored.reference);

        var result = diagnostic.evaluate(fact(current), scored.reference);
        assertThat(result.outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);
        assertThat(result.evaluation()).isNotNull();
        assertThat(result.evaluation().score()).isNull();
        assertThat(result.evaluation().status()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.DEGRADED);
        assertThat(result.evaluation().signals().getFirst().observed()).isTrue();
        assertThat(result.evaluation().signals().get(1).observed()).isTrue();
        assertThat(result.evaluation().signals().subList(2, 5)).allSatisfy(signal -> {
            assertThat(signal.support()).isEqualTo(com.frauddetection.common.events.device.policy.DeviceRiskSignalPolicy.Support.UNSUPPORTED);
            assertThat(signal.status()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE);
        });
        assertThat(result.toString()).doesNotContain("score-current", "transaction-current", "customer-1", "device-new");
        assertThat(scored.event.engineIntelligence()).isNull();
        assertThat(scored.event.mlPredictionEvidenceOmissionReason()).isEqualTo(MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED);
        assertThat(scored.event.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(scored.event.deviceInfo().deviceId()).isEqualTo("device-new"); // Existing public transaction input stays byte-for-byte represented.
        assertThat(scored.wireBytes).isEqualTo(new JacksonKafkaSerializer<TransactionScoredEvent>().serialize(topic, scored.event));
        assertThat(new String(scored.wireBytes, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("deviceRisk", "deviceDiagnostic", "deviceSignal");

        String institution = f.pseudonyms.institution(f.A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String eventKey = f.pseudonyms.event(f.A.institution, current.id());
        String deviceOccurrenceId = f.A.epoch().eventId(institution, sourceCode, eventKey);
        var database = f.ownerClient.getDatabase(f.database);
        var coverageCollection = database.getCollection("device_source_coverage");
        String coverageId = f.A.epoch().scopeId(institution, sourceCode);
        org.bson.Document coverageBefore = coverageCollection.find(com.mongodb.client.model.Filters.eq("_id", coverageId)).first();
        assertThat(coverageBefore).isNotNull();
        coverageCollection.updateOne(com.mongodb.client.model.Filters.eq("_id", coverageId),
                com.mongodb.client.model.Updates.set("coverageGeneration", coverageBefore.getLong("coverageGeneration") + 1));
        assertThat(diagnostic.evaluate(fact(current), scored.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        coverageCollection.replaceOne(com.mongodb.client.model.Filters.eq("_id", coverageId), coverageBefore);

        var occurrenceCollection = database.getCollection("device_occurrences");
        org.bson.Document persisted = occurrenceCollection.find(com.mongodb.client.model.Filters.eq("_id", deviceOccurrenceId)).first();
        assertThat(persisted).isNotNull();
        org.bson.Document forged = new org.bson.Document(persisted)
                .append("device", f.pseudonyms.device(f.A.institution, "tampered"));
        // Recompute the unkeyed snapshot and scored binding as an attacker with Mongo write access could.
        forged.put("stageACommitment", DeviceSnapshotVersion.commitment(forged));
        SourceLineage sourceAuthorization = privateSource.enriched(current.enriched());
        SourceScoredOccurrence sourceScore = occurrenceReader.find(sourceAuthorization, scored.reference).orElseThrow();
        forged.put("scoredBinding", DeviceScoredBinding.expected(forged, sourceScore));
        occurrenceCollection.updateOne(com.mongodb.client.model.Filters.eq("_id", deviceOccurrenceId),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("device", forged.getString("device")),
                        com.mongodb.client.model.Updates.set("stageACommitment", forged.getString("stageACommitment")),
                        com.mongodb.client.model.Updates.set("scoredBinding", forged.get("scoredBinding"))));
        assertThat(diagnostic.evaluate(fact(current), scored.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
    }

    @Test void lossOfAllPriorRepresentationsBeforeNextStageACannotBecomeFalseNovelty() throws Exception {
        Instant priorTime = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(3_600);
        var prior = f.event(f.A, "source-pre-stage-loss", "transaction-pre-stage-loss", "customer-1", "device-shared", 1, priorTime);
        var admission = f.adapter(f.A);
        var priorTicket = f.stage(admission, prior);
        var priorSeal = f.coverage(f.A, 1, f.A.start, priorTime);
        admission.seal(priorTicket, priorSeal.proof(), priorSeal.payload());
        assertThat(admission.commit(priorTicket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);

        String institution = f.pseudonyms.institution(f.A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String priorId = f.A.epoch().eventId(institution, sourceCode, f.pseudonyms.event(f.A.institution, prior.id()));
        var database = f.ownerClient.getDatabase(f.database);
        for (String collection : List.of("device_observations", "device_occurrences", "device_observation_candidates")) {
            assertThat(database.getCollection(collection).deleteOne(com.mongodb.client.model.Filters.eq("_id", priorId)).getDeletedCount())
                    .as("remove prior event from %s before the next Stage A transaction", collection).isEqualTo(1);
        }
        var coverage = database.getCollection("device_source_coverage").find(com.mongodb.client.model.Filters.eq("_id",
                f.A.epoch().scopeId(institution, sourceCode))).first();
        assertThat(coverage).isNotNull();
        assertThat(coverage.getLong("sealedSequence")).isEqualTo(1L);
        assertThat(coverage.getBoolean("invalidated", false)).isFalse();

        var current = f.event(f.A, "source-after-pre-stage-loss", "transaction-after-pre-stage-loss", "customer-1", "device-shared", 2,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var currentTicket = f.stage(admission, current);
        var currentSeal = f.coverage(f.A, 2, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(currentTicket, currentSeal.proof(), currentSeal.payload());
        admission.commit(currentTicket);
        var scored = publish(current, "initial", "score-after-pre-stage-loss");
        admission.bindScored(currentTicket, occurrenceReader, scored.reference);

        var result = diagnostic.evaluate(fact(current), scored.reference);
        assertThat(result.outcome()).as("a previously sealed in-window member vanished before Stage A")
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(result.evaluation()).isNotNull();
        assertThat(result.evaluation().signals().getFirst().status())
                .isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE);
        assertThat(result.evaluation().signals().getFirst().observed()).isNull();
        assertThat(result.evaluation().signals().get(1).status())
                .isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE);
    }

    @Test void missingCommittedCandidateCannotBeReportedAsExistingAfterAdapterRestart() throws Exception {
        var event = f.event(f.A, "source-candidate-loss", "transaction-candidate-loss", "customer-1", "device-candidate-loss", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var admission = f.adapter(f.A);
        var ticket = f.stage(admission, event);
        var seal = f.coverage(f.A, 1, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(ticket, seal.proof(), seal.payload());
        assertThat(admission.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);
        var scored = publish(event, "initial", "score-candidate-loss");
        admission.bindScored(ticket, occurrenceReader, scored.reference);

        String id = f.A.epoch().eventId(f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK"),
                f.pseudonyms.event(f.A.institution, event.id()));
        var candidates = f.ownerClient.getDatabase(f.database).getCollection("device_observation_candidates");
        assertThat(candidates.deleteOne(com.mongodb.client.model.Filters.eq("_id", id)).getDeletedCount()).isEqualTo(1);
        assertThat(f.ownerClient.getDatabase(f.database).getCollection("device_occurrences")
                .find(com.mongodb.client.model.Filters.eq("_id", id)).first()).isNotNull();
        assertThat(diagnostic.evaluate(fact(event), scored.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);

        var restartedAdmission = f.adapter(f.A);
        var replayTicket = f.stage(restartedAdmission, event);
        assertThatThrownBy(() -> restartedAdmission.commit(replayTicket))
                .hasMessage("DEVICE_STORE_CANDIDATE_MISSING");
        assertThat(candidates.countDocuments(com.mongodb.client.model.Filters.eq("_id", id))).isZero();
        assertThat(f.ownerClient.getDatabase(f.database).getCollection("device_occurrences")
                .countDocuments(com.mongodb.client.model.Filters.eq("_id", id))).isEqualTo(1);
        assertThat(f.ownerClient.getDatabase(f.database).getCollection("device_history_member_anchors")
                .countDocuments(com.mongodb.client.model.Filters.eq("_id", id))).isEqualTo(1);
    }

    @Test void rescoreAndUnprivilegedPrincipalsCannotReadOrRebindTheInitialOccurrence() throws Exception {
        var event = f.event(f.A, "source-one", "transaction-one", "customer-1", "device-one", 1, VerifiedDeviceAdmissionIntegrationTest.NOW);
        var admission = f.adapter(f.A);
        var ticket = f.stage(admission, event);
        var initial = publish(event, "initial", "score-initial");
        assertThat(diagnostic.evaluate(fact(event), initial.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.PENDING);
        var seal = f.coverage(f.A, 1, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(ticket, seal.proof(), seal.payload());
        admission.commit(ticket);
        admission.bindScored(ticket, occurrenceReader, initial.reference);

        var revoked = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database, readGrant(f.pseudonyms.customer(f.A.institution, "customer-1")),
                privateSource, occurrenceReader, new DeviceAssertionVerifier(f.origins, revokedCoverageGrants(), VerifiedDeviceAdmissionIntegrationTest.CLOCK),
                f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        assertThat(revoked.evaluate(fact(event), initial.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);

        var rescore = publish(event, "rescore-1", "score-rescore");
        assertThatThrownBy(() -> admission.bindScored(ticket, occurrenceReader, rescore.reference))
                .isInstanceOf(SecurityException.class).hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
        assertThat(diagnostic.evaluate(fact(event), rescore.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.DENIED);
        assertThat(diagnostic.evaluate(fact(event), new SourceOccurrenceAccess.Reference(initial.event.eventId(),
                initial.event.createdAt(), "0".repeat(64))).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.DENIED);
        assertThat(diagnostic.evaluate(fact(event), new SourceOccurrenceAccess.Reference("different-score-id",
                initial.event.createdAt(), initial.reference.fingerprint())).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.DENIED);
        var wrongCustomer = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database,
                readGrant(f.pseudonyms.customer(f.A.institution, "other-customer")), privateSource, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        assertThat(wrongCustomer.evaluate(fact(event), initial.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.DENIED);
        var wrongEpochGrant = new PrivateDeviceDiagnosticReader.ReadGrant("device_reader", PrivateDeviceDiagnosticReader.PURPOSE,
                PrivateDeviceDiagnosticReader.AUDIENCE, new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", "epoch-b"),
                "transaction-ingest", f.pseudonyms.customer(f.A.institution, "customer-1"), 1);
        var wrongEpoch = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database, wrongEpochGrant, privateSource, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        assertThat(wrongEpoch.evaluate(fact(event), initial.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.DENIED);

        assertThatThrownBy(() -> MongoSourceOccurrenceStore.readOnly(clients.writer, f.database, "scored_writer", f.originVerifier,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK, sourceCipher(),
                new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clients.deviceReader.getDatabase(f.database).getCollection("device_occurrences")
                .updateOne(new org.bson.Document(), new org.bson.Document("$set", new org.bson.Document("sourceEpoch", "forged"))))
                .isInstanceOf(com.mongodb.MongoCommandException.class);
        assertThatThrownBy(() -> clients.sourceReader.getDatabase(f.database).getCollection("source_scored_occurrences").updateOne(
                new org.bson.Document(), new org.bson.Document("$set", new org.bson.Document("state", "CONFLICT"))))
                .isInstanceOf(com.mongodb.MongoCommandException.class);
        assertThatThrownBy(() -> clients.sourceReader.getDatabase(f.database).getCollection("source_admission_receipts").updateOne(
                new org.bson.Document(), new org.bson.Document("$set", new org.bson.Document("state", "CONFLICT"))))
                .isInstanceOf(com.mongodb.MongoCommandException.class);
        assertThatThrownBy(() -> new PrivateDeviceDiagnosticReader(clients.writer, f.database,
                new PrivateDeviceDiagnosticReader.ReadGrant("scored_writer", PrivateDeviceDiagnosticReader.PURPOSE,
                        PrivateDeviceDiagnosticReader.AUDIENCE, new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                        "transaction-ingest", f.pseudonyms.customer(f.A.institution, "customer-1"), 1), f.reader, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK)).isInstanceOf(SecurityException.class);
        f.ownerClient.getDatabase(f.database).getCollection("source_admission_identities").updateOne(
                com.mongodb.client.model.Filters.eq("_id", SourceReceiptIdentity.identity(event.origin().scope())),
                com.mongodb.client.model.Updates.set("state", "CONFLICT"));
        assertThat(diagnostic.evaluate(fact(event), initial.reference).outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.DENIED);
    }

    @Test void shortCoverageAndAnEmptyWindowNeverBecomeAFalseNoveltyResult() throws Exception {
        var event = f.event(f.A, "source-short", "transaction-short", "customer-1", "device-short", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var admission = f.adapter(f.A);
        var ticket = f.stage(admission, event);
        var shortCoverage = f.coverage(f.A, 1, VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(30),
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(ticket, shortCoverage.proof(), shortCoverage.payload());
        admission.commit(ticket);
        var scored = publish(event, "initial", "score-short");
        admission.bindScored(ticket, occurrenceReader, scored.reference);

        var result = diagnostic.evaluate(fact(event), scored.reference);
        assertThat(result.outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(result.evaluation()).isNotNull();
        assertThat(result.evaluation().signals().getFirst().status()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE);
        assertThat(result.evaluation().signals().getFirst().observed()).isNull(); // Empty retained history cannot claim novelty.
        assertThat(result.evaluation().signals().get(1).status()).isEqualTo(com.frauddetection.common.events.engine.FraudEngineStatus.UNAVAILABLE);
        assertThat(result.evaluation().score()).isNull();
    }

    @Test void historyReaderCapIsInclusiveAtOneThousandAndOverflowFailsClosed() throws Exception {
        assertThat(PrivateDeviceDiagnosticReader.withinHistoryLimit(1)).isTrue();
        assertThat(PrivateDeviceDiagnosticReader.withinHistoryLimit(999)).isTrue();
        assertThat(PrivateDeviceDiagnosticReader.withinHistoryLimit(1_000)).isTrue();
        assertThat(PrivateDeviceDiagnosticReader.withinHistoryLimit(1_001)).isFalse();

        var current = f.event(f.A, "source-cap-current", "transaction-cap-current", "customer-1", "device-cap", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var admission = f.adapter(f.A);
        var ticket = f.stage(admission, current);
        var seal = f.coverage(f.A, 1, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(ticket, seal.proof(), seal.payload());
        admission.commit(ticket);
        var scored = publish(current, "initial", "score-cap-current");
        admission.bindScored(ticket, occurrenceReader, scored.reference);

        String institution = f.pseudonyms.institution(f.A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String customer = f.pseudonyms.customer(f.A.institution, "customer-1");
        String device = f.pseudonyms.device(f.A.institution, "device-cap");
        var observations = f.ownerClient.getDatabase(f.database).getCollection("device_observations");
        List<org.bson.Document> overflow = new ArrayList<>();
        for (int i = 0; i < 1_001; i++) {
            overflow.add(new org.bson.Document(f.A.epoch().document("cap-member-" + i, institution, sourceCode))
                    .append("keyVersion", 1).append("customer", customer).append("device", device)
                    .append("eventTime", java.util.Date.from(VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(60)))
                    .append("expireAt", java.util.Date.from(VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(3_600)))
                    .append("sourceEvent", "cap-source-" + i));
        }
        observations.insertMany(overflow, new com.mongodb.client.model.InsertManyOptions().bypassDocumentValidation(true));
        assertThat(observations.countDocuments(com.mongodb.client.model.Filters.and(f.A.epoch().filter(institution, sourceCode),
                com.mongodb.client.model.Filters.eq("keyVersion", 1), com.mongodb.client.model.Filters.eq("customer", customer),
                com.mongodb.client.model.Filters.lt("eventTime", java.util.Date.from(VerifiedDeviceAdmissionIntegrationTest.NOW))))).isEqualTo(1_001);

        var result = diagnostic.evaluate(fact(current), scored.reference);
        assertThat(result.outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(result.evaluation()).isNull(); // 1,001 rows cannot turn a truncated read into an empty/novel history.
    }

    @Test
    @EnabledIfSystemProperty(named = "fraud.device-risk.performance", matches = "true")
    void benchmarksAuthenticatedPrivateDiagnosticAtOneHundredFiveHundredAndOneThousandMembers() throws Exception {
        LongAdder sourceGrantRevalidations = new LongAdder();
        LongAdder coverageGrantRevalidations = new LongAdder();
        f.useBenchmarkLedgerTimeout(PERFORMANCE_FIXTURE_WRITER_TIMEOUT);
        var admission = f.adapter(f.A);
        f.sourceReceiptCapacity = 2_000;
        long sequence = 1;
        for (int historySize : List.of(100, 500, 1_000)) {
            String customerId = "benchmark-customer-" + historySize;
            String historyDeviceId = "benchmark-history-device-" + historySize;
            Instant historyTime = switch (historySize) {
                case 100 -> VerifiedDeviceAdmissionIntegrationTest.NOW.minus(Duration.ofHours(5));
                case 500 -> VerifiedDeviceAdmissionIntegrationTest.NOW.minus(Duration.ofHours(3));
                default -> VerifiedDeviceAdmissionIntegrationTest.NOW.minus(Duration.ofHours(1));
            };
            Instant currentTime = switch (historySize) {
                case 100 -> historyTime.plus(Duration.ofMinutes(10));
                case 500 -> historyTime.plus(Duration.ofMinutes(10));
                default -> VerifiedDeviceAdmissionIntegrationTest.NOW;
            };

            List<VerifiedDeviceAdmission.Ticket> historyTickets = new ArrayList<>(historySize);
            for (int member = 0; member < historySize; member++) {
                var event = f.event(f.A, "benchmark-history-" + historySize + '-' + member,
                        "benchmark-history-transaction-" + historySize + '-' + member, customerId, historyDeviceId,
                        sequence++, historyTime);
                historyTickets.add(f.stage(admission, event));
            }
            var historySeal = f.coverage(f.A, sequence - 1, f.A.start, historyTime);
            admission.seal(historyTickets.getLast(), historySeal.proof(), historySeal.payload());
            for (VerifiedDeviceAdmission.Ticket historyTicket : historyTickets) {
                assertThat(admission.commit(historyTicket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);
            }

            var current = f.event(f.A, "benchmark-current-" + historySize,
                    "benchmark-current-transaction-" + historySize, customerId, "benchmark-current-device-" + historySize,
                    sequence++, currentTime);
            var currentTicket = f.stage(admission, current);
            var currentSeal = f.coverage(f.A, sequence - 1, f.A.start, currentTime);
            admission.seal(currentTicket, currentSeal.proof(), currentSeal.payload());
            assertThat(admission.commit(currentTicket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);

            var readGrant = new PrivateDeviceDiagnosticReader.ReadGrant("device_reader", PrivateDeviceDiagnosticReader.PURPOSE,
                    PrivateDeviceDiagnosticReader.AUDIENCE,
                    new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                    "transaction-ingest", f.pseudonyms.customer(f.A.institution, customerId), 1);
            var measuredReader = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database, readGrant, privateSource,
                    occurrenceReader, new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK),
                    f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK, () -> { },
                    sourceGrantRevalidations::increment, coverageGrantRevalidations::increment);
            benchmarkHistorySize(historySize, current, currentTicket, admission, measuredReader,
                    sourceGrantRevalidations, coverageGrantRevalidations);
        }
    }

    private void benchmarkHistorySize(int historySize, VerifiedDeviceAdmissionIntegrationTest.Event current,
                                      VerifiedDeviceAdmission.Ticket currentTicket, VerifiedDeviceAdmission admission,
                                      PrivateDeviceDiagnosticReader measuredReader, LongAdder sourceGrantRevalidations,
                                      LongAdder coverageGrantRevalidations) throws Exception {
        var scored = publish(current, "initial", "benchmark-score-" + historySize);
        admission.bindScored(currentTicket, occurrenceReader, scored.reference);
        printHistoryQueryPlans(historySize, current);
        for (int concurrency : List.of(1, 2)) {
            List<BenchmarkSample> samples = measureDiagnostic(measuredReader, current, scored.reference,
                    PERFORMANCE_WARMUPS, PERFORMANCE_SAMPLES, concurrency,
                    sourceGrantRevalidations, coverageGrantRevalidations);
            var reads = f.MONGO.readOperationCounts();
            long unavailable = samples.stream().filter(sample -> sample.outcome() == PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE).count();
            double divisor = samples.size();
            double p95Millis = percentileMillis(samples, 95);
            assertThat(unavailable).as("history=%d concurrency=%d unavailable results", historySize, concurrency).isZero();
            assertThat(p95Millis).as("history=%d concurrency=%d p95 latency", historySize, concurrency)
                    .isLessThan(1_000.0);
            for (String collection : List.of("source_admission_receipts", "source_lineage_raw",
                    "source_lineage_enriched", "source_admission_identities")) {
                assertThat(readsFor(reads, collection) / divisor)
                        .as("history=%d concurrency=%d %s read commands per evaluation", historySize, concurrency, collection)
                        .isLessThanOrEqualTo(8.0);
            }
            System.out.printf(Locale.ROOT,
                    "DEVICE_PRIVATE_DIAGNOSTIC_BENCHMARK os=%s java=%s processors=%d mongo=mongo:7.0 kafka=apache/kafka:3.8.0 "
                            + "fixture_writer_timeout_s=%d reader_client_timeout_s=%d "
                            + "history_members=%d concurrency=%d samples=%d p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f "
                            + "min_ms=%.3f max_ms=%.3f unavailable_or_timeout=%d unavailable_or_timeout_rate=%.3f "
                            + "device_observations_reads_per_eval=%.1f device_occurrences_reads_per_eval=%.1f "
                            + "device_candidates_reads_per_eval=%.1f device_history_member_anchors_reads_per_eval=%.1f "
                            + "source_receipts_reads_per_eval=%.1f "
                            + "source_raw_reads_per_eval=%.1f source_enriched_reads_per_eval=%.1f "
                            + "source_identity_reads_per_eval=%.1f scored_occurrence_reads_per_eval=%.1f "
                            + "source_grant_revalidations_per_eval=%.1f coverage_grant_revalidations_per_eval=%.1f "
                            + "runtime_slo=NOT_MEASURED%n",
                    System.getProperty("os.name"), System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                    PERFORMANCE_FIXTURE_WRITER_TIMEOUT.toSeconds(), DeviceAuthenticatedMongo.DEFAULT_CLIENT_TIMEOUT.toSeconds(),
                    historySize, concurrency, samples.size(), percentileMillis(samples, 50), p95Millis,
                    percentileMillis(samples, 99), minimumMillis(samples), maximumMillis(samples), unavailable,
                    unavailable / divisor, readsFor(reads, "device_observations") / divisor,
                    readsFor(reads, "device_occurrences") / divisor, readsFor(reads, "device_observation_candidates") / divisor,
                    readsFor(reads, "device_history_member_anchors") / divisor,
                    readsFor(reads, "source_admission_receipts") / divisor, readsFor(reads, "source_lineage_raw") / divisor,
                    readsFor(reads, "source_lineage_enriched") / divisor, readsFor(reads, "source_admission_identities") / divisor,
                    readsFor(reads, "source_scored_occurrences") / divisor,
                    sourceGrantRevalidations.sum() / divisor, coverageGrantRevalidations.sum() / divisor);
        }
    }

    private void printHistoryQueryPlans(int historySize, VerifiedDeviceAdmissionIntegrationTest.Event current) {
        String institution = f.pseudonyms.institution(f.A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String customer = f.pseudonyms.customer(f.A.institution, current.raw().customerId());
        String device = f.pseudonyms.device(f.A.institution, current.raw().deviceInfo().deviceId());
        Instant asOfInstant = current.raw().transactionTimestamp();
        Instant windowStart = asOfInstant.minus(Duration.ofDays(30));
        Instant now = VerifiedDeviceAdmissionIntegrationTest.NOW;
        var database = f.ownerClient.getDatabase(f.database);
        var observationFilter = DeviceHistoryQueries.retainedMembership(f.A.epoch(), institution, sourceCode, 1,
                windowStart, asOfInstant, customer, device, now);
        var candidateReadFilter = DeviceHistoryQueries.membership(f.A.epoch(), institution, sourceCode, 1,
                windowStart, asOfInstant, customer, device);
        var stageACandidateFilter = DeviceHistoryQueries.committedStageAMembership(f.A.epoch(), institution, sourceCode, 1,
                windowStart, asOfInstant, customer, device, now);
        var eventOrder = DeviceHistoryQueries.historySort();
        QueryPlan stageBObservations = explain(database.getCollection("device_observations"), observationFilter,
                PrivateDeviceDiagnosticReader.OBSERVATION_HISTORY_PROJECTION,
                PrivateDeviceDiagnosticReader.MAX_VERIFIED_HISTORY_MEMBERS + 1, eventOrder);
        QueryPlan stageBOccurrences = explain(database.getCollection("device_occurrences"), observationFilter,
                PrivateDeviceDiagnosticReader.OCCURRENCE_HISTORY_PROJECTION,
                PrivateDeviceDiagnosticReader.MAX_VERIFIED_HISTORY_MEMBERS + 1, eventOrder);
        QueryPlan stageBCandidates = explain(database.getCollection("device_observation_candidates"), candidateReadFilter,
                PrivateDeviceDiagnosticReader.CANDIDATE_HISTORY_PROJECTION,
                PrivateDeviceDiagnosticReader.MAX_VERIFIED_HISTORY_MEMBERS + 1, eventOrder);
        QueryPlan stageAAnchors = explain(database.getCollection("device_history_member_anchors"), observationFilter,
                null, MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, eventOrder);
        QueryPlan stageAOccurrences = explain(database.getCollection("device_occurrences"), observationFilter,
                null, MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, eventOrder);
        QueryPlan stageACandidates = explain(database.getCollection("device_observation_candidates"), stageACandidateFilter,
                null, MongoDeviceObservationStore.MAX_HISTORY_MEMBERSHIP_ROWS + 1, eventOrder);
        assertThat(stageBObservations.indexes()).contains("observation_customer_window", "observation_device_window");
        assertThat(stageBOccurrences.indexes()).contains("occurrence_customer_window", "occurrence_device_window");
        assertThat(stageBCandidates.indexes()).containsAnyOf("candidate_customer_window", "candidate_device_window");
        assertThat(stageAAnchors.indexes()).contains("history_member_customer_window", "history_member_device_window");
        assertThat(stageAOccurrences.indexes()).contains("occurrence_customer_window", "occurrence_device_window");
        assertThat(stageACandidates.indexes()).containsAnyOf("candidate_customer_window", "candidate_device_window",
                "candidate_status_event_source_event");
        assertThat(List.of(stageBObservations, stageBOccurrences, stageBCandidates, stageAAnchors, stageAOccurrences,
                stageACandidates).stream().anyMatch(QueryPlan::collectionScan)).isFalse();
        printQueryPlan("STAGE_B", "observations", historySize, stageBObservations);
        printQueryPlan("STAGE_B", "occurrences", historySize, stageBOccurrences);
        printQueryPlan("STAGE_B", "all_candidates", historySize, stageBCandidates);
        printQueryPlan("STAGE_A", "anchors", historySize, stageAAnchors);
        printQueryPlan("STAGE_A", "occurrences", historySize, stageAOccurrences);
        printQueryPlan("STAGE_A", "committed_candidates", historySize, stageACandidates);
    }

    private void printQueryPlan(String stage, String collection, int historySize, QueryPlan plan) {
        System.out.printf(Locale.ROOT,
                "DEVICE_HISTORY_QUERY_PLAN stage=%s collection=%s history_members=%d indexes=%s docs_examined=%d "
                        + "keys_examined=%d docs_returned=%d in_memory_sort=%s collection_scan=%s%n",
                stage, collection, historySize, plan.indexes(), plan.documentsExamined(), plan.keysExamined(),
                plan.documentsReturned(), plan.inMemorySort(), plan.collectionScan());
    }

    private QueryPlan explain(com.mongodb.client.MongoCollection<org.bson.Document> collection,
                              org.bson.conversions.Bson filter, org.bson.conversions.Bson projection,
                              int limit, org.bson.conversions.Bson sort) {
        var query = collection.find(filter);
        if (projection != null) { query.projection(projection); }
        org.bson.Document explain = query.sort(sort).limit(limit)
                .explain(ExplainVerbosity.EXECUTION_STATS);
        org.bson.Document queryPlanner = findDocument(explain, "queryPlanner");
        org.bson.Document winningPlan = queryPlanner == null ? null : queryPlanner.get("winningPlan", org.bson.Document.class);
        List<String> indexes = new ArrayList<>();
        collectPlanIndexes(winningPlan, indexes);
        org.bson.Document stats = findDocument(explain, "executionStats");
        Object examined = stats == null ? null : stats.get("totalDocsExamined");
        Object keys = stats == null ? null : stats.get("totalKeysExamined");
        Object returned = stats == null ? null : stats.get("nReturned");
        return new QueryPlan(indexes.stream().distinct().toList(), examined instanceof Number number ? number.longValue() : -1,
                keys instanceof Number number ? number.longValue() : -1,
                returned instanceof Number number ? number.longValue() : -1,
                containsPlanStage(winningPlan, "SORT"), containsPlanStage(winningPlan, "COLLSCAN"));
    }

    private List<BenchmarkSample> measureDiagnostic(PrivateDeviceDiagnosticReader reader,
                                                     VerifiedDeviceAdmissionIntegrationTest.Event event,
                                                     SourceOccurrenceAccess.Reference reference, int warmups,
                                                     int sampleCount, int concurrency, LongAdder sourceGrantRevalidations,
                                                     LongAdder coverageGrantRevalidations) throws Exception {
        for (int i = 0; i < warmups; i++) {
            if (concurrency == 1) {
                timedEvaluation(reader, event, reference);
            } else {
                runConcurrentBatch(reader, event, reference, concurrency);
            }
        }
        sourceGrantRevalidations.reset();
        coverageGrantRevalidations.reset();
        f.MONGO.resetReadOperations();
        List<BenchmarkSample> samples = new ArrayList<>(sampleCount);
        if (concurrency == 1) {
            for (int i = 0; i < sampleCount; i++) { samples.add(timedEvaluation(reader, event, reference)); }
            return samples;
        }
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        try {
            for (int round = 0; round < sampleCount / concurrency; round++) {
                List<Future<BenchmarkSample>> batch = new ArrayList<>(concurrency);
                for (int worker = 0; worker < concurrency; worker++) {
                    batch.add(workers.submit(() -> timedEvaluation(reader, event, reference)));
                }
                for (Future<BenchmarkSample> sample : batch) { samples.add(sample.get(12, TimeUnit.SECONDS)); }
            }
        } finally {
            workers.shutdownNow();
        }
        return samples;
    }

    private List<BenchmarkSample> runConcurrentBatch(PrivateDeviceDiagnosticReader reader,
                                                     VerifiedDeviceAdmissionIntegrationTest.Event event,
                                                     SourceOccurrenceAccess.Reference reference, int concurrency) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<BenchmarkSample>> batch = new ArrayList<>(concurrency);
            for (int worker = 0; worker < concurrency; worker++) {
                batch.add(workers.submit(() -> timedEvaluation(reader, event, reference)));
            }
            List<BenchmarkSample> results = new ArrayList<>(concurrency);
            for (Future<BenchmarkSample> sample : batch) { results.add(sample.get(12, TimeUnit.SECONDS)); }
            return results;
        } finally {
            workers.shutdownNow();
        }
    }

    private BenchmarkSample timedEvaluation(PrivateDeviceDiagnosticReader reader,
                                            VerifiedDeviceAdmissionIntegrationTest.Event event,
                                            SourceOccurrenceAccess.Reference reference) {
        long started = System.nanoTime();
        PrivateDeviceDiagnosticReader.Result result = reader.evaluate(fact(event), reference);
        return new BenchmarkSample(System.nanoTime() - started, result.outcome());
    }

    private static long readsFor(Map<String, Long> counts, String collection) {
        return counts.entrySet().stream().filter(entry -> entry.getKey().startsWith(collection + "."))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private static double percentileMillis(List<BenchmarkSample> samples, int percentile) {
        List<Long> sorted = samples.stream().map(BenchmarkSample::elapsedNanos).sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    private static double minimumMillis(List<BenchmarkSample> samples) {
        return samples.stream().mapToLong(BenchmarkSample::elapsedNanos).min().orElseThrow() / 1_000_000.0;
    }

    private static double maximumMillis(List<BenchmarkSample> samples) {
        return samples.stream().mapToLong(BenchmarkSample::elapsedNanos).max().orElseThrow() / 1_000_000.0;
    }

    private static org.bson.Document findDocument(Object value, String fieldName) {
        if (value instanceof org.bson.Document document) {
            Object nested = document.get(fieldName);
            if (nested instanceof org.bson.Document found) { return found; }
            for (Object child : document.values()) {
                org.bson.Document found = findDocument(child, fieldName);
                if (found != null) { return found; }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                org.bson.Document found = findDocument(child, fieldName);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private static void collectPlanIndexes(Object value, List<String> indexes) {
        if (value instanceof org.bson.Document document) {
            if ("IXSCAN".equals(document.getString("stage")) && document.getString("indexName") != null) {
                indexes.add(document.getString("indexName"));
            }
            document.values().forEach(child -> collectPlanIndexes(child, indexes));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectPlanIndexes(child, indexes));
        }
    }

    private static boolean containsPlanStage(Object value, String stage) {
        if (value instanceof org.bson.Document document) {
            if (stage.equals(document.getString("stage"))) { return true; }
            return document.values().stream().anyMatch(child -> containsPlanStage(child, stage));
        }
        if (value instanceof List<?> list) { return list.stream().anyMatch(child -> containsPlanStage(child, stage)); }
        return false;
    }

    @Test void partialDeletionOfLinkedHistoryInvalidatesEverySubsequentDiagnosticRead() throws Exception {
        Instant priorTime = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(3_600);
        var prior = f.event(f.A, "source-retained", "transaction-retained", "customer-1", "device-old", 1, priorTime);
        var admission = f.adapter(f.A);
        var priorTicket = f.stage(admission, prior);
        var priorSeal = f.coverage(f.A, 1, f.A.start, priorTime);
        admission.seal(priorTicket, priorSeal.proof(), priorSeal.payload());
        admission.commit(priorTicket);
        var priorScore = publish(prior, "initial", "score-retained");
        admission.bindScored(priorTicket, occurrenceReader, priorScore.reference);

        Instant sharedTime = VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(3_500);
        var deviceOnlyPrior = f.event(f.A, "source-a-device-only", "transaction-device-only", "customer-2", "device-new", 2, sharedTime);
        var overlappingPrior = f.event(f.A, "source-b-overlap", "transaction-overlap", "customer-1", "device-new", 3, sharedTime);
        var deviceOnlyTicket = f.stage(admission, deviceOnlyPrior);
        var overlappingTicket = f.stage(admission, overlappingPrior);
        var sharedSeal = f.coverage(f.A, 3, f.A.start, sharedTime);
        admission.seal(deviceOnlyTicket, sharedSeal.proof(), sharedSeal.payload());
        admission.commit(deviceOnlyTicket);
        admission.commit(overlappingTicket);

        var current = f.event(f.A, "source-after-retention", "transaction-after-retention", "customer-1", "device-new", 4,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var currentTicket = f.stage(admission, current);
        var currentSeal = f.coverage(f.A, 4, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(currentTicket, currentSeal.proof(), currentSeal.payload());
        admission.commit(currentTicket);
        var currentScore = publish(current, "initial", "score-after-retention");
        admission.bindScored(currentTicket, occurrenceReader, currentScore.reference);

        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);
        String currentId = f.A.epoch().eventId(f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK"),
                f.pseudonyms.event(f.A.institution, current.id()));
        String priorId = f.A.epoch().eventId(f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK"),
                f.pseudonyms.event(f.A.institution, prior.id()));
        var database = f.ownerClient.getDatabase(f.database);
        var observations = database.getCollection("device_observations");
        var occurrences = database.getCollection("device_occurrences");
        var candidates = database.getCollection("device_observation_candidates");
        var idFilter = com.mongodb.client.model.Filters.eq("_id", priorId);
        var priorObservation = observations.find(idFilter).first();
        var priorOccurrence = occurrences.find(idFilter).first();
        var priorCandidate = candidates.find(idFilter).first();
        assertThat(priorObservation).isNotNull();
        assertThat(priorOccurrence).isNotNull();
        assertThat(priorCandidate).isNotNull();
        assertThat(priorCandidate.getString("status")).isEqualTo("COMMITTED");
        assertThat(occurrences.find(com.mongodb.client.model.Filters.eq("_id", currentId)).first().getInteger("historyMembershipCount"))
                .isEqualTo(3); // Customer-only, device-only and overlapping membership contributes a deduplicated union.

        // Mutations occur after a complete read snapshot and before the final fresh-snapshot revalidation.
        var deleteDuringRead = readerAfterSnapshot(() -> observations.deleteOne(idFilter));
        assertThat(deleteDuringRead.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        observations.insertOne(priorObservation);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);

        var coverageCollection = database.getCollection("device_source_coverage");
        String coverageId = f.A.epoch().scopeId(f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK"));
        var coverageBeforeRace = coverageCollection.find(com.mongodb.client.model.Filters.eq("_id", coverageId)).first();
        assertThat(coverageBeforeRace).isNotNull();
        var changeCoverageDuringRead = readerAfterSnapshot(() -> coverageCollection.updateOne(
                com.mongodb.client.model.Filters.eq("_id", coverageId),
                com.mongodb.client.model.Updates.combine(
                        com.mongodb.client.model.Updates.set("revision", coverageBeforeRace.getLong("revision") + 1),
                        com.mongodb.client.model.Updates.set("coverageGeneration", coverageBeforeRace.getLong("coverageGeneration") + 1))));
        assertThat(changeCoverageDuringRead.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        coverageCollection.replaceOne(com.mongodb.client.model.Filters.eq("_id", coverageId), coverageBeforeRace);

        String currentDevice = f.pseudonyms.device(f.A.institution, "device-new");
        String tombstoneId = f.A.epoch().scopeId(f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK")) + ":1:" + currentDevice;
        var tombstones = database.getCollection("device_operational_revocation_tombstones");
        var revokeDuringRead = readerAfterSnapshot(() -> tombstones.insertOne(new org.bson.Document(
                f.A.epoch().document(tombstoneId, f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK")))
                .append("expireAt", java.util.Date.from(VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(3_600)))));
        assertThat(revokeDuringRead.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        tombstones.deleteOne(com.mongodb.client.model.Filters.eq("_id", tombstoneId));

        var identityCollection = database.getCollection("source_admission_identities");
        var identityFilter = com.mongodb.client.model.Filters.eq("_id", SourceReceiptIdentity.identity(current.origin().scope()));
        var identityBeforeRace = identityCollection.find(identityFilter).first();
        assertThat(identityBeforeRace).isNotNull();
        var revokeSourceDuringRead = readerAfterSnapshot(() -> identityCollection.updateOne(identityFilter,
                com.mongodb.client.model.Updates.set("state", "CONFLICT")));
        assertThat(revokeSourceDuringRead.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        identityCollection.replaceOne(identityFilter, identityBeforeRace);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);

        candidates.updateOne(idFilter, com.mongodb.client.model.Updates.set("status", "STAGED"));
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // A BSON status label is not commit authority.
        candidates.replaceOne(idFilter, priorCandidate);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);

        candidates.updateOne(idFilter, com.mongodb.client.model.Updates.set("transaction", "f".repeat(64)));
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // Candidate MAC inputs must still match the authenticated occurrence.
        candidates.replaceOne(idFilter, priorCandidate);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);

        candidates.updateOne(idFilter, com.mongodb.client.model.Updates.set("provenance.receiptId", "f".repeat(64)));
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // Copies still require identical, committed provenance.
        candidates.replaceOne(idFilter, priorCandidate);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);

        assertThat(observations.deleteOne(idFilter).getDeletedCount()).isEqualTo(1);

        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // No stale in-process diagnostic cache.

        observations.insertOne(priorObservation);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);
        assertThat(occurrences.deleteOne(idFilter).getDeletedCount()).isEqualTo(1);

        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // No stale in-process diagnostic cache.

        assertThat(candidates.find(idFilter).first().getString("status")).isEqualTo("COMMITTED");
        assertThat(observations.deleteOne(idFilter).getDeletedCount()).isEqualTo(1);
        var dualDeletion = diagnostic.evaluate(fact(current), currentScore.reference);
        assertThat(dualDeletion.outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(dualDeletion.evaluation()).isNull(); // Missing history cannot produce a false AVAILABLE signal.
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // Restart-free repeated reads stay fail-closed.

        observations.insertOne(priorObservation);
        occurrences.insertOne(priorOccurrence);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);
        assertThat(candidates.deleteOne(idFilter).getDeletedCount()).isEqualTo(1);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // Candidate loss is independently detected.
        candidates.insertOne(priorCandidate);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);

        observations.deleteOne(idFilter);
        occurrences.deleteOne(idFilter);
        candidates.deleteOne(idFilter);
        var allCopiesDeleted = diagnostic.evaluate(fact(current), currentScore.reference);
        assertThat(allCopiesDeleted.outcome()).isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(allCopiesDeleted.evaluation()).isNull();
        var restartedReader = new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database,
                readGrant(f.pseudonyms.customer(f.A.institution, "customer-1")), privateSource, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK), f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        assertThat(restartedReader.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // No persisted gap can be hidden by a reader restart.
    }

    private Published publish(VerifiedDeviceAdmissionIntegrationTest.Event source, String runId, String scoreId) throws Exception {
        TransactionScoredEvent candidate = score(source, scoreId);
        var pending = occurrenceWriter.prepare(source.enriched(), runId, candidate);
        assertThat(pending.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PENDING);
        var claim = occurrenceWriter.claim(pending.id());
        assertThat(claim.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHING);
        if (!topicCreated) {
            try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
                topicCreated = true;
            }
        }
        byte[] bytes = new JacksonKafkaSerializer<TransactionScoredEvent>().serialize(topic, claim.event());
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic, claim.event().eventId(), bytes)).get(15, TimeUnit.SECONDS);
        }
        var acknowledged = new JacksonKafkaDeserializer<>(TransactionScoredEvent.class).deserialize(topic, bytes);
        var published = occurrenceWriter.confirm(pending.id(), acknowledged, claim.claimId());
        assertThat(published.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHED);
        ScoredRecord consumedRecord = consume(candidate.eventId());
        TransactionScoredEvent consumed = consumedRecord.event;
        var reference = new SourceOccurrenceAccess.Reference(consumed.eventId(), consumed.createdAt(), SourceScoredOccurrence.fingerprint(consumed));
        return new Published(consumed, reference, consumedRecord.bytes);
    }

    private ScoredRecord consume(String expectedId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "device-private-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < until) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    if (expectedId.equals(record.key())) {
                        return new ScoredRecord(new JacksonKafkaDeserializer<>(TransactionScoredEvent.class).deserialize(topic, record.value()), record.value());
                    }
                }
            }
        }
        throw new AssertionError("SCORED_KAFKA_RECORD_NOT_RECEIVED");
    }

    private TransactionScoredEvent score(VerifiedDeviceAdmissionIntegrationTest.Event source, String id) {
        var e = source.enriched();
        return new TransactionScoredEvent(id, e.transactionId(), e.correlationId(), e.customerId(), e.accountId(),
                VerifiedDeviceAdmissionIntegrationTest.NOW, e.transactionTimestamp(), e.transactionAmount(), e.merchantInfo(), e.deviceInfo(),
                e.locationInfo(), e.customerContext(), 0.12d, RiskLevel.LOW, "RULES_ONLY", null, null,
                VerifiedDeviceAdmissionIntegrationTest.NOW, List.of(), Map.of("source", "rules"), e.featureSnapshot(), false, List.of(),
                null, null, MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED, null);
    }

    private PrivateDeviceDiagnosticReader.FactReference fact(VerifiedDeviceAdmissionIntegrationTest.Event event) {
        return new PrivateDeviceDiagnosticReader.FactReference(SourceReceiptIdentity.identity(event.origin().scope()),
                SourceEventCommitment.raw(event.raw()), SourceEventCommitment.enriched(event.enriched()));
    }

    private PrivateDeviceDiagnosticReader.ReadGrant readGrant(String customerToken) {
        return new PrivateDeviceDiagnosticReader.ReadGrant("device_reader", PrivateDeviceDiagnosticReader.PURPOSE,
                PrivateDeviceDiagnosticReader.AUDIENCE, new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "transaction-ingest", customerToken, 1);
    }

    private PrivateDeviceDiagnosticReader readerAfterSnapshot(Runnable hook) {
        return new PrivateDeviceDiagnosticReader(clients.deviceReader, f.database,
                readGrant(f.pseudonyms.customer(f.A.institution, "customer-1")), privateSource, occurrenceReader,
                new DeviceAssertionVerifier(f.origins, f.grants, VerifiedDeviceAdmissionIntegrationTest.CLOCK),
                f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK, hook);
    }

    private List<DeviceAssertionVerifier.Grant> revokedCoverageGrants() {
        return f.grants.stream().map(g -> {
            if (g.role() != DeviceAssertionVerifier.Role.COVERAGE) { return g; }
            var k = g.key();
            var revoked = new SourceVerificationProperties.TrustedKey(k.issuer(), k.institution(), k.source(), k.sourceEpoch(), k.signingKeyVersion(),
                    k.algorithm(), k.publicKeyBase64(), k.notBefore(), k.notAfter(), true, k.purpose(), k.audience(), k.policyVersion());
            return new DeviceAssertionVerifier.Grant(g.role(), revoked, g.sourceScope(), g.population(), g.pseudonymKeyVersion(),
                    g.bindingAuthority(), g.providerBasis());
        }).toList();
    }

    private SourceOccurrenceCipher sourceCipher() {
        byte[] key = VerifiedDeviceAdmissionIntegrationTest.randomKey();
        return new SourceOccurrenceCipher("test-key", Map.of("test-key", Base64.getEncoder().encodeToString(key)));
    }

    private record Published(TransactionScoredEvent event, SourceOccurrenceAccess.Reference reference, byte[] wireBytes) { }
    private record ScoredRecord(TransactionScoredEvent event, byte[] bytes) { }
    private record BenchmarkSample(long elapsedNanos, PrivateDeviceDiagnosticReader.Outcome outcome) { }
    private record QueryPlan(List<String> indexes, long documentsExamined, long keysExamined, long documentsReturned,
                             boolean inMemorySort, boolean collectionScan) { }
    private record MongoClientHandle(com.mongodb.client.MongoClient writer, com.mongodb.client.MongoClient sourceReader,
                                    com.mongodb.client.MongoClient deviceReader) {
        void close() { writer.close(); sourceReader.close(); deviceReader.close(); }
    }
}
