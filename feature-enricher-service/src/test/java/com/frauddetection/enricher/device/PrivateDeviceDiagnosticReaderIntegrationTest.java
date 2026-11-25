package com.frauddetection.enricher.device;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoClient;
import com.frauddetection.common.events.contract.TransactionScoredEvent;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.kafka.JacksonKafkaDeserializer;
import com.frauddetection.common.events.kafka.JacksonKafkaSerializer;
import com.frauddetection.common.events.source.*;
import com.frauddetection.common.testsupport.container.KafkaImageReference;
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

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Private diagnostic proof: real signed source, authenticated Mongo, published SCORED bytes and exact binding. */
@Tag("UNTRUSTED_TEST_ONLY")
class PrivateDeviceDiagnosticReaderIntegrationTest {
    private static final int PERFORMANCE_WARMUPS = 2;
    private static final int PERFORMANCE_SAMPLES = 20;
    private static final Duration PERFORMANCE_FIXTURE_WRITER_TIMEOUT = Duration.ofSeconds(30);
    private static final KafkaContainer KAFKA = new KafkaContainer(KafkaImageReference.dockerImageName())
            .withImagePullPolicy(KafkaImageReference.localOnlyPullPolicy());
    private VerifiedDeviceAdmissionIntegrationTest f;
    private MongoSourceOccurrenceStore occurrenceWriter;
    private SourceOccurrenceAccess occurrenceReader;
    private SourceOccurrenceCipher occurrenceCipher;
    private MongoSourceLineage privateSource;
    private MongoClientHandle clients;
    private PrivateDeviceDiagnosticReader diagnostic;
    String topic;
    boolean topicCreated;
    private KafkaProducer<String, byte[]> scoredProducer;
    private KafkaConsumer<String, byte[]> scoredConsumer;
    private final Map<String, ScoredRecord> consumedScoredEvents = new HashMap<>();

    VerifiedDeviceAdmissionIntegrationTest fixture() { return f; }
    SourceOccurrenceAccess occurrenceReader() { return occurrenceReader; }
    SourceOccurrenceCipher occurrenceCipher() { return occurrenceCipher; }
    MongoSourceLineage privateSource() { return privateSource; }

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
        occurrenceCipher = sourceCipher();
        occurrenceWriter = new MongoSourceOccurrenceStore(clients.writer, f.database, "scored_writer", f.originVerifier,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK, occurrenceCipher, Duration.ofHours(1));
        occurrenceReader = MongoSourceOccurrenceStore.readOnly(clients.sourceReader, f.database, "source_diagnostic_reader", f.originVerifier,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK, occurrenceCipher,
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
        cleanUp(true);
    }

    void cleanUpForContainerShutdown() {
        cleanUp(false);
    }

    private void cleanUp(boolean dropDatabase) {
        try {
            if (scoredProducer != null) { scoredProducer.close(); }
        } finally {
            try {
                if (scoredConsumer != null) { scoredConsumer.close(); }
            } finally {
                try {
                    if (dropDatabase && f != null) { f.cleanup(); }
                } finally {
                    if (clients != null) { clients.close(); }
                }
            }
        }
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

    @Test void graphOwnerMongoStallsAtDeviceSourceAndInitialScoreStagesRespectOneDeadline() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("owner_budget_stalls", "graph-device-owner-budget-stalls",
                "fdp156-device-owner-budget");
        String sourcePrincipal = "graph_source_budget_stalls";
        String scorePrincipal = "graph_score_budget_stalls";
        MongoClient sourceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceReader(f.ownerClient,
                f.database, sourcePrincipal, List.of(), "fdp156-source-owner-budget");
        MongoClient scoreClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceReader(f.ownerClient,
                f.database, scorePrincipal, List.of(), "fdp156-score-owner-budget");
        MongoSourceLineage currentSource = new MongoSourceLineage(sourceClient, f.database, sourcePrincipal,
                MongoSourceLineage.Stage.SCORER, f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        SourceOccurrenceAccess currentScores = MongoSourceOccurrenceStore.readOnly(scoreClient, f.database, scorePrincipal,
                f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK, occurrenceCipher,
                new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest"));
        DeviceGraphRelationAuthority budgetedOwner = DeviceGraphRelationAuthority.testOnly(fixture.ownerClient(),
                f.database, "device_graph_owner_budget_stalls", f.A.epoch(), currentSource, f.origins, f.grants,
                currentScores, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);

        List<Map.Entry<String, String>> stages = List.of(
                Map.entry("Device", "fdp156-device-owner-budget"),
                Map.entry("Source", "fdp156-source-owner-budget"),
                Map.entry("INITIAL_RUN score", "fdp156-score-owner-budget"));
        for (var stage : stages) {
            configureFindStall(stage.getValue());
            VerifiedDeviceAdmissionIntegrationTest.MONGO.resetReadOperations();
            long started = System.nanoTime();
            DeviceGraphRelationResolution result;
            long elapsedMillis;
            try {
                result = budgetedOwner.resolve(fixture.edge().evidence(), fixture.relationGrant(), fixture.readGrant(),
                        fixture.principal(), Duration.ofSeconds(5));
                elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            } finally {
                disableFindStall();
            }
            long readCommands = VerifiedDeviceAdmissionIntegrationTest.MONGO.readOperationCounts().values().stream()
                    .mapToLong(Long::longValue).sum();
            System.out.printf("FDP156-REV2-004 stage=%s state=%s reason=%s elapsedMs=%d readCommands=%d%n",
                    stage.getKey(), result.state(), result.reason(), elapsedMillis, readCommands);

            assertThat(result.state()).as(stage.getKey()).isEqualTo(DeviceGraphRelationResolution.State.UNAVAILABLE);
            assertThat(result.reason()).as(stage.getKey())
                    .isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.DEADLINE_EXCEEDED);
            assertThat(result.relation()).as(stage.getKey()).isEmpty();
            assertThat(elapsedMillis).as(stage.getKey() + " bounded wall time with 5s socket/CSOT tolerance")
                    .isLessThan(6_500);
            assertThat(readCommands).as(stage.getKey() + " bounded Mongo reads").isBetween(1L, 32L);
            assertThat(DeviceGraphRelationAuthority.activeOwnerReadWorkers())
                    .as(stage.getKey() + " bounded workers")
                    .isLessThanOrEqualTo(DeviceGraphRelationAuthority.maxOwnerReadWorkers());
            assertThat(DeviceGraphRelationAuthority.queuedOwnerReadWorkers()).as(stage.getKey() + " no queued reads").isZero();
        }
        long workerDrainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (DeviceGraphRelationAuthority.activeOwnerReadWorkers() > 0 && System.nanoTime() < workerDrainDeadline) {
            Thread.sleep(25);
        }
        assertThat(DeviceGraphRelationAuthority.activeOwnerReadWorkers()).as("stalled Mongo reads release workers").isZero();
    }

    private void configureFindStall(String applicationName) {
        f.ownerClient.getDatabase("admin").runCommand(new org.bson.Document("configureFailPoint", "failCommand")
                .append("mode", new org.bson.Document("times", 1))
                .append("data", new org.bson.Document("failCommands", List.of("find"))
                        .append("appName", applicationName).append("blockConnection", true).append("blockTimeMS", 8_000)));
    }

    private void disableFindStall() {
        f.ownerClient.getDatabase("admin").runCommand(new org.bson.Document("configureFailPoint", "failCommand")
                .append("mode", "off"));
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

    @Test void deviceGraphBridgeRevalidatesSignedOwnerEvidenceAndExactInitialScoreAfterRestart() throws Exception {
        var event = f.event(f.A, "graph-device-event-one", "graph-device-transaction-one", "customer-1",
                "graph-device-id", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(60));
        var secondEvent = f.event(f.A, "graph-device-event-two", "graph-device-transaction-two", "customer-1",
                "graph-device-id", 2, VerifiedDeviceAdmissionIntegrationTest.NOW);
        var admission = f.adapter(f.A);
        var firstTicket = f.stage(admission, event);
        var secondTicket = f.stage(admission, secondEvent);
        var seal = f.coverage(f.A, 2, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(firstTicket, seal.proof(), seal.payload());
        admission.commit(firstTicket);
        admission.commit(secondTicket);
        var scored = publish(event, "initial", "graph-device-score-one");
        admission.bindScored(firstTicket, occurrenceReader, scored.reference);
        var secondScored = publish(secondEvent, "initial", "graph-device-score-two");
        admission.bindScored(secondTicket, occurrenceReader, secondScored.reference);

        String institutionToken = f.pseudonyms.institution(f.A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String ownerReference = f.A.epoch().eventId(institutionToken, sourceCode,
                f.pseudonyms.event(f.A.institution, event.id()));
        String secondOwnerReference = f.A.epoch().eventId(institutionToken, sourceCode,
                f.pseudonyms.event(f.A.institution, secondEvent.id()));
        var sourceScope = new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch);
        var relationGrant = DeviceGraphRelationGrant.testOnly(DeviceEpoch.digest("test-graph-relation-grant", f.database),
                sourceScope, "transaction-ingest", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        var readGrant = DeviceGraphReadGrant.testOnly("graph_reader", sourceScope, "transaction-ingest", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));

        assertThatThrownBy(() -> DeviceGraphRelationAuthority.testOnly(clients.deviceReader, f.database, "device_reader",
                f.A.epoch(), privateSource, f.origins, f.grants, occurrenceReader, f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK))
                .hasMessage("DEVICE_GRAPH_READ_ONLY_PRINCIPAL_REQUIRED");

        var firstReader = DeviceGraphRelationAuthority.testOnly(
                VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient, f.database, "device_graph_reader"),
                f.database, "device_graph_reader", f.A.epoch(), privateSource, f.origins, f.grants,
                occurrenceReader, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        var admissionResolution = firstReader.admit(ownerReference, relationGrant);
        assertThat(admissionResolution.state()).isEqualTo(DeviceGraphRelationResolution.State.VERIFIED);
        var admitted = admissionResolution.relation().orElseThrow();
        assertThat(firstReader.admit(DeviceEpoch.digest("guessed-device-owner-reference", "missing"), relationGrant).state())
                .isEqualTo(DeviceGraphRelationResolution.State.UNAVAILABLE);
        assertThat(admitted.reference().scoredOccurrence().runId()).isEqualTo("initial");
        assertThat(admitted.reference().scoredOccurrence().createdAt()).isEqualTo(scored.event.createdAt());
        assertThat(admitted.reference().associatedIdentityToken())
                .isEqualTo(f.pseudonyms.device(f.A.institution, "graph-device-id"));
        assertThat(admitted.toString()).doesNotContain("graph-device-id", "graph-device-transaction");

        var graphEdges = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.admitAll(firstReader,
                relationGrant, readGrant, List.of(ownerReference, secondOwnerReference), "graph_reader",
                VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5), VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        assertThat(graphEdges).hasSize(2);
        assertThat(graphEdges.get(0).to().canonicalId()).isEqualTo(graphEdges.get(1).to().canonicalId());
        assertThat(graphEdges.get(0).canonicalId()).isNotEqualTo(graphEdges.get(1).canonicalId());
        assertThat(graphEdges).allSatisfy(edge -> assertThat(edge.evidence().scoredOccurrence().runId()).isEqualTo("initial"));

        List<MongoClient> restartedOwnerClients = new ArrayList<>();
        com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade.Result persistedRoundTrip;
        try {
            persistedRoundTrip = com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade
                    .persistAndReadAfterAdapterRestart(firstReader, () -> {
                        MongoClient restartedSourceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO
                                .occurrenceReader(f.ownerClient, f.database, "graph_source_restart", List.of());
                        MongoClient restartedDeviceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO
                                .graphReader(f.ownerClient, f.database, "device_graph_restart_after_graph");
                        restartedOwnerClients.add(restartedSourceClient);
                        restartedOwnerClients.add(restartedDeviceClient);
                        SourceOccurrenceAccess restartedOccurrences = MongoSourceOccurrenceStore.readOnly(
                                restartedSourceClient, f.database, "graph_source_restart", f.originVerifier,
                                VerifiedDeviceAdmissionIntegrationTest.CLOCK, occurrenceCipher,
                                new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE,
                                        "transaction-ingest"));
                        MongoSourceLineage restartedSource = new MongoSourceLineage(restartedSourceClient, f.database,
                                "graph_source_restart", MongoSourceLineage.Stage.SCORER, f.originVerifier,
                                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
                        return DeviceGraphRelationAuthority.testOnly(restartedDeviceClient, f.database,
                                "device_graph_restart_after_graph", f.A.epoch(), restartedSource, f.origins, f.grants,
                                restartedOccurrences, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
                    }, relationGrant, readGrant, List.of(ownerReference, secondOwnerReference), "graph_reader",
                    VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                    VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(6));
        } finally {
            restartedOwnerClients.forEach(MongoClient::close);
        }
        assertThat(persistedRoundTrip.appendStates()).containsExactly("APPENDED", "APPENDED");
        assertThat(persistedRoundTrip.replayStates()).containsExactly("EXISTING", "EXISTING");
        assertThat(persistedRoundTrip.persistedRows()).isEqualTo(2);
        assertThat(persistedRoundTrip.readOutcomes()).hasSize(2).allSatisfy(outcome ->
                assertThat(outcome.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED));
        var roundTripEdges = persistedRoundTrip.readOutcomes().stream().flatMap(outcome -> outcome.edges().stream()).toList();
        assertThat(roundTripEdges).containsExactly(graphEdges.get(0), graphEdges.get(1));
        assertThat(roundTripEdges.get(0).to().canonicalId()).isEqualTo(roundTripEdges.get(1).to().canonicalId());

        var restartedReader = DeviceGraphRelationAuthority.testOnly(
                VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient, f.database, "device_graph_restart"),
                f.database, "device_graph_restart", f.A.epoch(), privateSource, f.origins, f.grants,
                occurrenceReader, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        Instant graphReadAt = VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5);
        AtomicReference<DeviceGraphRelationPort> liveOwner = new AtomicReference<>(restartedReader);
        var currentGraphRead = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                liveOwner::get, relationGrant, readGrant, graphEdges.get(0), "graph_reader", graphReadAt, null);
        assertThat(currentGraphRead.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(currentGraphRead.edges()).containsExactly(graphEdges.get(0));

        var revokedDuringRead = DeviceGraphRelationAuthority.testOnly(
                VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient, f.database, "device_graph_revoked_during_read"),
                f.database, "device_graph_revoked_during_read", f.A.epoch(), privateSource, f.origins,
                revokedCoverageGrants(), occurrenceReader, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        liveOwner.set(restartedReader);
        var revokedGraphRead = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                liveOwner::get, relationGrant, readGrant, graphEdges.get(0), "graph_reader", graphReadAt,
                () -> liveOwner.set(revokedDuringRead));
        assertThat(revokedGraphRead.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.DENIED);
        assertThat(revokedGraphRead.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.AUTHORITY_REVOKED);
        assertThat(revokedGraphRead.edges()).isEmpty();

        var resolved = restartedReader.resolve(admitted.reference(), relationGrant, readGrant, "graph_reader")
                .relation().orElseThrow();
        assertThat(resolved.reference()).isEqualTo(admitted.reference());
        var resolvedSecond = restartedReader.resolve(graphEdges.get(1).evidence(), relationGrant, readGrant, "graph_reader")
                .relation().orElseThrow();
        assertThat(resolvedSecond.reference()).isEqualTo(graphEdges.get(1).evidence());
        var secondGraphRead = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                () -> restartedReader, relationGrant, readGrant, graphEdges.get(1), "graph_reader", graphReadAt, null);
        assertThat(secondGraphRead.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED);
        assertThat(secondGraphRead.edges()).containsExactly(graphEdges.get(1));

        var forged = admitted.reference();
        var changedDevice = new com.frauddetection.enricher.graph.GraphEvidenceRef(forged.scope(), forged.occurrence(),
                DeviceEpoch.digest("forged-graph-device", "different-device"), forged.pseudonymKeyVersion(),
                forged.sourceReceiptReference(), forged.rawCommitment(), forged.enrichedCommitment(),
                forged.ownerAssertionReference(), forged.coverageReference(), forged.policyReference(),
                forged.scoredOccurrence(), forged.stageASnapshotCommitment(), forged.coverageFrom(), forged.asOf(),
                forged.coverageGeneration(), forged.expiresAt());
        assertThat(restartedReader.resolve(changedDevice, relationGrant, readGrant, "graph_reader").state())
                .isEqualTo(DeviceGraphRelationResolution.State.UNAVAILABLE);
        assertThat(restartedReader.resolve(admitted.reference(), null, readGrant, "graph_reader").state())
                .isEqualTo(DeviceGraphRelationResolution.State.DENIED);
        assertThat(restartedReader.resolve(admitted.reference(), relationGrant, readGrant, "someone_else").state())
                .isEqualTo(DeviceGraphRelationResolution.State.DENIED);
        var wrongEpochGrant = DeviceGraphRelationGrant.testOnly(DeviceEpoch.digest("test-wrong-epoch-grant", f.database),
                new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", "epoch-b"), "transaction-ingest", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        assertThat(restartedReader.resolve(admitted.reference(), wrongEpochGrant, readGrant, "graph_reader").state())
                .isEqualTo(DeviceGraphRelationResolution.State.DENIED);
        assertThat(restartedReader.resolve(admitted.reference(), wrongEpochGrant, readGrant, "graph_reader").reason())
                .isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.GRANT_DENIED);
        var wrongAudienceGrant = DeviceGraphRelationGrant.testOnly(
                DeviceEpoch.digest("test-wrong-audience-grant", f.database),
                new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "untrusted-audience", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        var wrongAudience = restartedReader.resolve(admitted.reference(), wrongAudienceGrant, readGrant, "graph_reader");
        assertThat(wrongAudience.state()).isEqualTo(DeviceGraphRelationResolution.State.DENIED);
        assertThat(wrongAudience.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.GRANT_DENIED);

        var revokedCoverage = DeviceGraphRelationAuthority.testOnly(
                VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient, f.database, "device_graph_revoked"),
                f.database, "device_graph_revoked", f.A.epoch(), privateSource, f.origins,
                revokedCoverageGrants(), occurrenceReader, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        var revokedResolution = revokedCoverage.resolve(admitted.reference(), relationGrant, readGrant, "graph_reader");
        assertThat(revokedResolution.state()).isEqualTo(DeviceGraphRelationResolution.State.DENIED);
        assertThat(revokedResolution.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.AUTHORITY_REVOKED);

        var scoredOccurrences = f.ownerClient.getDatabase(f.database).getCollection("source_scored_occurrences");
        assertThat(scoredOccurrences.updateOne(com.mongodb.client.model.Filters.eq("eventId", scored.event.eventId()),
                com.mongodb.client.model.Updates.set("fingerprint", "0".repeat(64))).getModifiedCount()).isEqualTo(1);
        List<MongoClient> mismatchRestartClients = new ArrayList<>();
        try {
            MongoClient mismatchSourceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO
                    .occurrenceReader(f.ownerClient, f.database, "graph_source_mismatch_restart", List.of());
            MongoClient mismatchDeviceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO
                    .graphReader(f.ownerClient, f.database, "device_graph_mismatch_restart");
            mismatchRestartClients.add(mismatchSourceClient);
            mismatchRestartClients.add(mismatchDeviceClient);
            var mismatchOccurrences = com.frauddetection.common.events.source.MongoSourceOccurrenceStore.readOnly(
                    mismatchSourceClient, f.database, "graph_source_mismatch_restart", f.originVerifier,
                    VerifiedDeviceAdmissionIntegrationTest.CLOCK, occurrenceCipher,
                    new com.frauddetection.common.events.source.SourceSignatureVerifier.Context(
                            DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest"));
            var mismatchSource = new com.frauddetection.common.events.source.MongoSourceLineage(mismatchSourceClient,
                    f.database, "graph_source_mismatch_restart",
                    com.frauddetection.common.events.source.MongoSourceLineage.Stage.SCORER, f.originVerifier,
                    VerifiedDeviceAdmissionIntegrationTest.CLOCK);
            var afterMismatchRestart = DeviceGraphRelationAuthority.testOnly(mismatchDeviceClient, f.database,
                    "device_graph_mismatch_restart", f.A.epoch(), mismatchSource, f.origins, f.grants,
                    mismatchOccurrences, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
            assertThat(afterMismatchRestart.resolve(graphEdges.get(0).evidence(), relationGrant, readGrant, "graph_reader").state())
                    .isEqualTo(DeviceGraphRelationResolution.State.UNAVAILABLE);
            var unavailableRead = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                    () -> afterMismatchRestart, relationGrant, readGrant, graphEdges.get(0), "graph_reader", graphReadAt, null);
            assertThat(unavailableRead.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.UNAVAILABLE);
            assertThat(unavailableRead.edges()).isEmpty();

            assertThat(scoredOccurrences.updateOne(com.mongodb.client.model.Filters.eq("eventId", scored.event.eventId()),
                    com.mongodb.client.model.Updates.set("fingerprint", scored.reference.fingerprint())).getModifiedCount())
                    .isEqualTo(1);
            assertThat(afterMismatchRestart.resolve(graphEdges.get(0).evidence(), relationGrant, readGrant, "graph_reader").state())
                    .isEqualTo(DeviceGraphRelationResolution.State.VERIFIED);
            var recoveredRead = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                    () -> afterMismatchRestart, relationGrant, readGrant, graphEdges.get(0), "graph_reader", graphReadAt, null);
            assertThat(recoveredRead.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED);
            assertThat(recoveredRead.edges()).containsExactly(graphEdges.get(0));
        } finally {
            mismatchRestartClients.forEach(MongoClient::close);
        }
    }

    @Test void historicalGraphEdgeRemainsReadableAfterASecondValidSignedCoverageSeal() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("coverage_extension", "graph-device-coverage-extension");
        List<MongoClient> restartedOwnerClients = new ArrayList<>();
        AtomicReference<VerifiedDeviceGraphRelation> currentCoverageEvidence = new AtomicReference<>();
        com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade.Result result;
        try {
            result = com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade
                    .persistAndReadAfterAdapterRestart(fixture.owner(),
                            () -> restartedGraphOwner("device_graph_c2_restart", restartedOwnerClients),
                            fixture.relationGrant(), fixture.relationGrant(), fixture.readGrant(),
                            List.of(fixture.edge().evidence().ownerAssertionReference()), fixture.principal(),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(6), () -> {
                                try {
                                    var extension = f.event(f.A, "graph-c2-event-two", "graph-c2-transaction-two",
                                            "customer-1", "graph-device-coverage-extension", 2,
                                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(30));
                                    var adapter = f.adapter(f.A);
                                    var ticket = f.stage(adapter, extension);
                                    var c2 = f.coverage(f.A, 2, f.A.start,
                                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(30));
                                    adapter.seal(ticket, c2.proof(), c2.payload());
                                    adapter.commit(ticket);
                                    var scored = publish(extension, "initial", "graph-c2-score-two");
                                    adapter.bindScored(ticket, occurrenceReader, scored.reference);
                                    var c3Event = f.event(f.A, "graph-c3-event-three", "graph-c3-transaction-three",
                                            "customer-1", "graph-device-coverage-extension-c3", 3,
                                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(60));
                                    var c3Ticket = f.stage(adapter, c3Event);
                                    var c3 = f.coverage(f.A, 3, f.A.start,
                                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(60));
                                    adapter.seal(c3Ticket, c3.proof(), c3.payload());
                                    adapter.commit(c3Ticket);
                                    var c3Scored = publish(c3Event, "initial", "graph-c3-score-three");
                                    adapter.bindScored(c3Ticket, occurrenceReader, c3Scored.reference);
                                    currentCoverageEvidence.set(fixture.owner().admit(
                                            fixture.edge().evidence().ownerAssertionReference(), fixture.relationGrant())
                                            .relation().orElseThrow());
                                } catch (Exception failure) {
                                    throw new AssertionError("SIGNED_C2_FIXTURE_FAILED", failure);
                                }
                            });
        } finally {
            restartedOwnerClients.forEach(MongoClient::close);
        }

        assertThat(currentCoverageEvidence.get()).isNotNull();
        assertThat(currentCoverageEvidence.get().reference().canonicalReferenceId())
                .isEqualTo(fixture.edge().evidence().canonicalReferenceId());
        var historicalResolution = fixture.owner().resolve(fixture.edge().evidence(), fixture.relationGrant(),
                fixture.readGrant(), fixture.principal());
        assertThat(historicalResolution.state()).isEqualTo(DeviceGraphRelationResolution.State.VERIFIED);
        assertThat(historicalResolution.relation()).get().satisfies(relation ->
                assertThat(relation.reference().canonicalReferenceId())
                        .isEqualTo(fixture.edge().evidence().canonicalReferenceId()));
        assertThat(result.persistedRows()).isEqualTo(1);
        assertThat(result.postChangeReplayStates()).containsExactly("EXISTING");
        assertThat(result.replayPreservedEvidence()).isTrue();
        assertThat(result.readOutcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED);
            assertThat(outcome.edges()).containsExactly(fixture.edge());
        });
    }

    @Test void exactGraphScoreMovesFromPendingToVerifiedWhenPublicationBecomesDurable() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("score_publication", "graph-device-score-publication");
        AtomicBoolean published = new AtomicBoolean();
        SourceOccurrenceAccess statusAwareOccurrences = new SourceOccurrenceAccess() {
            @Override public Optional<SourceScoredOccurrence> find(SourceLineage authorization,
                                                                   SourceOccurrenceAccess.Reference occurrence) {
                return occurrenceReader.find(authorization, occurrence);
            }

            @Override public Resolution resolve(SourceLineage authorization,
                                                SourceOccurrenceAccess.Reference occurrence) {
                return published.get() ? occurrenceReader.resolve(authorization, occurrence) : Resolution.pending();
            }
        };
        DeviceGraphRelationAuthority owner = DeviceGraphRelationAuthority.testOnly(fixture.ownerClient(), f.database,
                "device_graph_score_publication", f.A.epoch(), privateSource, f.origins, f.grants,
                statusAwareOccurrences, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);

        var pending = owner.resolve(fixture.edge().evidence(), fixture.relationGrant(), fixture.readGrant(),
                fixture.principal());
        assertThat(pending.state()).isEqualTo(DeviceGraphRelationResolution.State.PENDING);
        assertThat(pending.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.SOURCE_UNAVAILABLE);
        assertThat(pending.relation()).isEmpty();

        published.set(true);
        var verified = owner.resolve(fixture.edge().evidence(), fixture.relationGrant(), fixture.readGrant(),
                fixture.principal());
        assertThat(verified.state()).isEqualTo(DeviceGraphRelationResolution.State.VERIFIED);
        assertThat(verified.relation()).get().satisfies(relation -> {
            assertThat(relation.reference()).isEqualTo(fixture.edge().evidence());
            assertThat(relation.validUntil()).isAfter(relation.reference().asOf());
            assertThat(relation.validUntil().compareTo(relation.reference().expiresAt())).isLessThanOrEqualTo(0);
        });
    }

    @Test void explicitSourceReceiptRevocationIsDeniedWithAuthorityReason() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("source_revoked", "graph-device-source-revoked");
        var update = f.ownerClient.getDatabase(f.database).getCollection("source_admission_identities")
                .updateOne(com.mongodb.client.model.Filters.eq("_id", fixture.edge().evidence().sourceReceiptReference()),
                        com.mongodb.client.model.Updates.set("state", "TOMBSTONED"));
        assertThat(update.getModifiedCount()).isEqualTo(1);

        var resolution = fixture.owner().resolve(fixture.edge().evidence(), fixture.relationGrant(),
                fixture.readGrant(), fixture.principal());

        assertThat(resolution.state()).isEqualTo(DeviceGraphRelationResolution.State.DENIED);
        assertThat(resolution.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.AUTHORITY_REVOKED);
    }

    @Test void historicalGraphEdgeRemainsReadableAfterEquivalentRelationGrantRenewal() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("grant_renewal", "graph-device-grant-renewal");
        var renewedGrant = DeviceGraphRelationGrant.testOnly(
                DeviceEpoch.digest("test-graph-relation-grant-renewed", f.database),
                new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "transaction-ingest", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        var renewedEvidence = fixture.owner().admit(fixture.edge().evidence().ownerAssertionReference(), renewedGrant)
                .relation().orElseThrow();
        assertThat(renewedEvidence.reference().policyReference())
                .isEqualTo(fixture.edge().evidence().policyReference());
        var historicGrantResolution = fixture.owner().resolve(fixture.edge().evidence(), renewedGrant,
                fixture.readGrant(), fixture.principal());
        assertThat(historicGrantResolution.state()).isEqualTo(DeviceGraphRelationResolution.State.VERIFIED);
        List<MongoClient> restartedOwnerClients = new ArrayList<>();
        com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade.Result result;
        try {
            result = com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade
                    .persistAndReadAfterAdapterRestart(fixture.owner(),
                            () -> restartedGraphOwner("device_graph_grant_restart", restartedOwnerClients),
                            fixture.relationGrant(), renewedGrant, fixture.readGrant(),
                            List.of(fixture.edge().evidence().ownerAssertionReference()), fixture.principal(),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(6), () -> { });
        } finally {
            restartedOwnerClients.forEach(MongoClient::close);
        }

        assertThat(result.persistedRows()).isEqualTo(1);
        assertThat(result.postChangeReplayStates()).containsExactly("EXISTING");
        assertThat(result.replayPreservedEvidence()).isTrue();
        assertThat(result.readOutcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.VERIFIED_BOUNDED);
            assertThat(outcome.edges()).containsExactly(fixture.edge());
        });
    }

    @Test void transientOwnerMongoOutageDoesNotDurablyRevokeAnEdgeAndRecoveryKeepsItActive() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("owner_outage", "graph-device-owner-outage");
        AtomicReference<DeviceGraphRelationPort> currentOwner = new AtomicReference<>(fixture.owner());
        List<MongoClient> restartedOwnerClients = new ArrayList<>();
        DeviceGraphReadGrant reconciliationReadGrant = DeviceGraphReadGrant.testOnly(
                "graph_lifecycle_reconciler",
                new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "transaction-ingest", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade.LifecycleResult result;
        try {
            result = com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade
                    .appendThenReconcileAfterOwnerChange(currentOwner::get, fixture.relationGrant(), fixture.readGrant(),
                            reconciliationReadGrant,
                            fixture.edge().evidence().ownerAssertionReference(), fixture.principal(),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofHours(1)),
                            fixture.ownerClient()::close,
                            () -> currentOwner.set(restartedGraphOwner("device_graph_outage_recovery", restartedOwnerClients)));
        } finally {
            restartedOwnerClients.forEach(MongoClient::close);
        }

        assertThat(result.appendState()).isEqualTo("APPENDED");
        assertThat(result.reconciliationState()).isEqualTo("UNAVAILABLE");
        assertThat(result.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(result.lifecycleAfterFirstReconciliation()).isEqualTo("ACTIVE");
        assertThat(result.recoveryState()).isEqualTo("EXISTING");
        assertThat(result.lifecycle()).isEqualTo("ACTIVE");
        assertThat(result.persistedRows()).isEqualTo(1);
    }

    @Test void conflictingValidSignedSourceBindingFencesOneGraphRelationAfterOwnerRestart() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("signed_source_conflict", "graph-device-signed-source-conflict");
        AtomicReference<DeviceGraphRelationPort> currentOwner = new AtomicReference<>(fixture.owner());
        List<MongoClient> restartedOwnerClients = new ArrayList<>();
        String reconciliationPrincipal = "graph_lifecycle_reconciler";
        DeviceGraphReadGrant reconciliationReadGrant = DeviceGraphReadGrant.testOnly(reconciliationPrincipal,
                new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "transaction-ingest", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade.LifecycleResult result;
        try {
            result = com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade
                    .appendThenReconcileAfterOwnerChange(currentOwner::get, fixture.relationGrant(), fixture.readGrant(),
                            reconciliationReadGrant, fixture.edge().evidence().ownerAssertionReference(),
                            fixture.principal(), VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(1), () -> {
                                var conflictingProof = conflictingSignedProof(fixture.event());
                                var ownerStatus = com.frauddetection.ingest.source.DeviceTestSourceReceipts
                                        .admitConflictingSignedProof(f.ownerClient, f.database,
                                                VerifiedDeviceAdmissionIntegrationTest.CLOCK, f.originVerifier,
                                                conflictingProof, f.sourceReceiptCapacity);
                                assertThat(ownerStatus).isEqualTo("CONFLICT");
                                currentOwner.set(restartedGraphOwner("device_graph_signed_source_conflict_restart",
                                        restartedOwnerClients));
                                assertThat(currentOwner.get().resolve(fixture.edge().evidence(), fixture.relationGrant(),
                                        reconciliationReadGrant, reconciliationPrincipal).state())
                                        .isEqualTo(DeviceGraphRelationResolution.State.CONFLICT);
                            },
                            () -> currentOwner.set(restartedGraphOwner("device_graph_signed_conflict_recovery",
                                    restartedOwnerClients)));
        } finally {
            restartedOwnerClients.forEach(MongoClient::close);
        }

        assertThat(result.appendState()).isEqualTo("APPENDED");
        assertThat(result.reconciliationState()).isEqualTo("FENCED");
        assertThat(result.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.CONFLICTING_EVIDENCE);
        assertThat(result.lifecycleAfterFirstReconciliation()).isEqualTo("CONFLICT");
        assertThat(result.recoveryState()).isEqualTo("FENCED");
        assertThat(result.recoveryReason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.CONFLICTING_EVIDENCE);
        assertThat(result.lifecycle()).isEqualTo("CONFLICT");
        assertThat(result.replayAdmissionState()).isEqualTo("CONFLICT");
        assertThat(result.edgesAfterFence()).isZero();
        assertThat(result.immutableEvidencePreserved()).isTrue();
        assertThat(result.persistedRows()).isEqualTo(1);
    }

    @Test void explicitLateSourceRevocationFencesAfterOneHourAndOwnerAdapterRestart() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("late_source_revoked", "graph-device-late-source-revoked");
        AtomicReference<DeviceGraphRelationPort> currentOwner = new AtomicReference<>(fixture.owner());
        List<MongoClient> restartedOwnerClients = new ArrayList<>();
        DeviceGraphReadGrant reconciliationReadGrant = DeviceGraphReadGrant.testOnly(
                "graph_lifecycle_reconciler",
                new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch),
                "transaction-ingest", 1, VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade.LifecycleResult result;
        try {
            result = com.frauddetection.enricher.graph.GraphDeviceMongoVerticalTestFacade
                    .appendThenReconcileAfterOwnerChange(currentOwner::get, fixture.relationGrant(), fixture.readGrant(),
                            reconciliationReadGrant,
                            fixture.edge().evidence().ownerAssertionReference(), fixture.principal(),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                            VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofHours(1)),
                            () -> {
                                f.ownerClient.getDatabase(f.database).getCollection("source_admission_identities")
                                        .updateOne(com.mongodb.client.model.Filters.eq("_id",
                                                        fixture.edge().evidence().sourceReceiptReference()),
                                                com.mongodb.client.model.Updates.set("state", "TOMBSTONED"));
                                fixture.ownerClient().close();
                            },
                            () -> currentOwner.set(restartedGraphOwner("device_graph_late_revoke_restart", restartedOwnerClients)));
        } finally {
            restartedOwnerClients.forEach(MongoClient::close);
        }

        assertThat(result.appendState()).isEqualTo("APPENDED");
        assertThat(result.reconciliationState()).isEqualTo("UNAVAILABLE");
        assertThat(result.lifecycleAfterFirstReconciliation()).isEqualTo("ACTIVE");
        assertThat(result.recoveryState()).isEqualTo("FENCED");
        assertThat(result.recoveryReason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.AUTHORITY_REVOKED);
        assertThat(result.lifecycle()).isEqualTo("REVOKED");
        assertThat(result.persistedRows()).isEqualTo(1);
    }

    @Test void graphReadRejectsDeletedSourceReceiptAfterFirstOwnerValidation() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("receipt_deleted", "graph-device-receipt");

        var result = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                () -> fixture.owner(), fixture.relationGrant(), fixture.readGrant(), fixture.edge(), fixture.principal(),
                fixture.readAt(), () -> assertThat(f.ownerClient.getDatabase(f.database)
                        .getCollection("source_admission_receipts").deleteOne(com.mongodb.client.model.Filters.eq(
                                "_id", fixture.edge().evidence().sourceReceiptReference())).getDeletedCount()).isEqualTo(1));

        assertThat(result.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.SOURCE_UNAVAILABLE);
        assertThat(result.edges()).isEmpty();
    }

    @Test void graphReadRejectsDeletedHistoryMemberAfterFirstOwnerValidation() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("member_deleted", "graph-device-member");

        var result = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                () -> fixture.owner(), fixture.relationGrant(), fixture.readGrant(), fixture.edge(), fixture.principal(),
                fixture.readAt(), () -> assertThat(f.ownerClient.getDatabase(f.database)
                        .getCollection("device_history_member_anchors").deleteOne(com.mongodb.client.model.Filters.eq(
                                "_id", fixture.edge().evidence().ownerAssertionReference())).getDeletedCount()).isEqualTo(1));

        assertThat(result.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(result.edges()).isEmpty();
    }

    @Test void graphReadRejectsLoweredCoverageWatermarkAfterFirstOwnerValidation() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("watermark_lowered", "graph-device-watermark");
        String coverageId = f.A.epoch().scopeId(f.pseudonyms.institution(f.A.institution),
                DevicePseudonyms.sourceCode("BANK"));

        var result = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                () -> fixture.owner(), fixture.relationGrant(), fixture.readGrant(), fixture.edge(), fixture.principal(),
                fixture.readAt(), () -> assertThat(f.ownerClient.getDatabase(f.database)
                        .getCollection("device_source_coverage").updateOne(com.mongodb.client.model.Filters.eq("_id", coverageId),
                                com.mongodb.client.model.Updates.set("watermark", java.util.Date.from(
                                        fixture.edge().evidence().occurrence().eventTime().minusMillis(1)))).getModifiedCount())
                        .isEqualTo(1));

        assertThat(result.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.COVERAGE_UNAVAILABLE);
        assertThat(result.edges()).isEmpty();
    }

    @Test void graphReadRejectsPseudonymKeyRotationAfterFirstOwnerValidation() throws Exception {
        GraphReaderFixture fixture = graphReaderFixture("pseudonym_rotated", "graph-device-rotation");
        var rotatedOwner = DeviceGraphRelationAuthority.testOnly(
                VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient, f.database,
                        "device_graph_rotated_pseudonym"), f.database, "device_graph_rotated_pseudonym", f.A.epoch(),
                privateSource, f.origins, f.grants, occurrenceReader,
                new DevicePseudonyms(VerifiedDeviceAdmissionIntegrationTest.randomKey(), f.pseudonyms.version()),
                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        AtomicReference<DeviceGraphRelationPort> currentOwner = new AtomicReference<>(fixture.owner());

        var result = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.readWithDeviceOwner(
                currentOwner::get, fixture.relationGrant(), fixture.readGrant(), fixture.edge(), fixture.principal(),
                fixture.readAt(), () -> currentOwner.set(rotatedOwner));

        assertThat(result.state()).isEqualTo(com.frauddetection.enricher.graph.GraphProjectionState.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo(com.frauddetection.enricher.graph.GraphReadReason.OWNER_UNAVAILABLE);
        assertThat(result.edges()).isEmpty();
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
                    "DEVICE_PRIVATE_DIAGNOSTIC_BENCHMARK os=%s java=%s processors=%d mongo=mongo:7.0@sha256:41e8195b2791692695e1dc9bd21cd51d0a3af05fff8ace9304a5c1acf94f1ae1 kafka=%s "
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
                    KafkaImageReference.IMAGE,
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

    Published publish(VerifiedDeviceAdmissionIntegrationTest.Event source, String runId, String scoreId) throws Exception {
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
        scoredProducer().send(new ProducerRecord<>(topic, claim.event().eventId(), bytes))
                .get(15, TimeUnit.SECONDS);
        var acknowledged = new JacksonKafkaDeserializer<>(TransactionScoredEvent.class).deserialize(topic, bytes);
        var published = occurrenceWriter.confirm(pending.id(), acknowledged, claim.claimId());
        assertThat(published.status()).isEqualTo(MongoSourceOccurrenceStore.Status.PUBLISHED);
        ScoredRecord consumedRecord = consume(candidate.eventId());
        TransactionScoredEvent consumed = consumedRecord.event;
        var reference = new SourceOccurrenceAccess.Reference(consumed.eventId(), consumed.createdAt(), SourceScoredOccurrence.fingerprint(consumed));
        return new Published(consumed, reference, consumedRecord.bytes);
    }

    private ScoredRecord consume(String expectedId) {
        ScoredRecord buffered = consumedScoredEvents.remove(expectedId);
        if (buffered != null) { return buffered; }
        KafkaConsumer<String, byte[]> consumer = scoredConsumer();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            var records = consumer.poll(Duration.ofMillis(250));
            for (var record : records) {
                ScoredRecord consumed = new ScoredRecord(
                        new JacksonKafkaDeserializer<>(TransactionScoredEvent.class).deserialize(topic, record.value()),
                        record.value());
                if (expectedId.equals(record.key())) { return consumed; }
                if (record.key() != null) { consumedScoredEvents.putIfAbsent(record.key(), consumed); }
            }
        }
        throw new AssertionError("SCORED_KAFKA_RECORD_NOT_RECEIVED");
    }

    private KafkaProducer<String, byte[]> scoredProducer() {
        if (scoredProducer == null) {
            scoredProducer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.ACKS_CONFIG, "all"), new StringSerializer(), new ByteArraySerializer());
        }
        return scoredProducer;
    }

    private KafkaConsumer<String, byte[]> scoredConsumer() {
        if (scoredConsumer == null) {
            Properties properties = new Properties();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, "device-private-test-" + UUID.randomUUID());
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
            scoredConsumer = new KafkaConsumer<>(properties);
            scoredConsumer.subscribe(List.of(topic));
        }
        return scoredConsumer;
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

    private GraphReaderFixture graphReaderFixture(String suffix, String deviceId) throws Exception {
        return graphReaderFixture(suffix, deviceId, null);
    }

    private GraphReaderFixture graphReaderFixture(String suffix, String deviceId, String mongoApplicationName) throws Exception {
        var event = f.event(f.A, "graph-read-" + suffix, "graph-read-transaction-" + suffix,
                "customer-1", deviceId, 1, VerifiedDeviceAdmissionIntegrationTest.NOW.minusSeconds(60));
        var admission = f.adapter(f.A);
        var ticket = f.stage(admission, event);
        var coverage = f.coverage(f.A, 1, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(ticket, coverage.proof(), coverage.payload());
        admission.commit(ticket);
        var scored = publish(event, "initial", "graph-read-score-" + suffix);
        admission.bindScored(ticket, occurrenceReader, scored.reference);

        String institutionToken = f.pseudonyms.institution(f.A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String ownerReference = f.A.epoch().eventId(institutionToken, sourceCode,
                f.pseudonyms.event(f.A.institution, event.id()));
        var sourceScope = new DeviceAssertions.Scope("source-issuer", f.A.institution, "BANK", f.A.epoch);
        var relationGrant = DeviceGraphRelationGrant.testOnly(
                DeviceEpoch.digest("test-graph-read-grant", f.database, suffix), sourceScope, "transaction-ingest", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        var readGrant = DeviceGraphReadGrant.testOnly("graph_reader", sourceScope, "transaction-ingest", 1,
                VerifiedDeviceAdmissionIntegrationTest.NOW.plus(Duration.ofDays(40)));
        String mongoPrincipal = "device_graph_" + suffix;
        MongoClient ownerClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient, f.database,
                mongoPrincipal, mongoApplicationName);
        var owner = DeviceGraphRelationAuthority.testOnly(ownerClient, f.database, mongoPrincipal, f.A.epoch(),
                privateSource, f.origins, f.grants, occurrenceReader, f.pseudonyms,
                VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        var edge = com.frauddetection.enricher.graph.GraphEvidenceAdmissionTestFacade.admitAll(owner, relationGrant,
                readGrant, List.of(ownerReference), "graph_reader", VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5),
                VerifiedDeviceAdmissionIntegrationTest.CLOCK).getFirst();
        return new GraphReaderFixture(owner, ownerClient, relationGrant, readGrant, edge, "graph_reader",
                VerifiedDeviceAdmissionIntegrationTest.NOW.plusSeconds(5), event);
    }

    private DeviceGraphRelationAuthority restartedGraphOwner(String principal, List<MongoClient> ownedClients) {
        String sourcePrincipal = "graph_source_" + principal;
        MongoClient sourceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.occurrenceReader(f.ownerClient,
                f.database, sourcePrincipal, List.of());
        MongoClient deviceClient = VerifiedDeviceAdmissionIntegrationTest.MONGO.graphReader(f.ownerClient,
                f.database, principal);
        ownedClients.add(sourceClient);
        ownedClients.add(deviceClient);
        var restartedOccurrences = MongoSourceOccurrenceStore.readOnly(sourceClient, f.database, sourcePrincipal,
                f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK, occurrenceCipher,
                new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest"));
        var restartedSource = new MongoSourceLineage(sourceClient, f.database, sourcePrincipal,
                MongoSourceLineage.Stage.SCORER, f.originVerifier, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
        return DeviceGraphRelationAuthority.testOnly(deviceClient, f.database, principal, f.A.epoch(), restartedSource,
                f.origins, f.grants, restartedOccurrences, f.pseudonyms, VerifiedDeviceAdmissionIntegrationTest.CLOCK);
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

    SourceSignatureVerifier.Verified conflictingSignedProof(VerifiedDeviceAdmissionIntegrationTest.Event event) {
        try {
            var changed = (tools.jackson.databind.node.ObjectNode) VerifiedDeviceAdmissionIntegrationTest.JSON
                    .readTree(event.sourcePayload());
            changed.put("transactionId", event.raw().transactionId() + "-conflicting-signed-payload");
            byte[] payload = VerifiedDeviceAdmissionIntegrationTest.JSON.writeValueAsBytes(changed);
            SourceScope original = event.origin().scope();
            SourceScope conflicting = new SourceScope(original.issuer(), original.institution(), original.source(),
                    original.sourceEpoch(), original.signingKeyVersion(), original.sourceEventId(), original.eventTime(),
                    original.purpose(), original.audience(), SourceSignedInput.payloadCommitment(payload));
            byte[] proof = VerifiedDeviceAdmissionIntegrationTest.sign(conflicting, payload, f.originKey);
            var verified = f.originVerifier.verify(proof, payload,
                    new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest"));
            assertThat(verified).isInstanceOf(SourceSignatureVerifier.Verified.class);
            return (SourceSignatureVerifier.Verified) verified;
        } catch (Exception failure) {
            throw new IllegalStateException("TEST_SOURCE_CONFLICT_PROOF_FAILED", failure);
        }
    }

    private SourceOccurrenceCipher sourceCipher() {
        byte[] key = VerifiedDeviceAdmissionIntegrationTest.randomKey();
        return new SourceOccurrenceCipher("test-key", Map.of("test-key", Base64.getEncoder().encodeToString(key)));
    }

    record Published(TransactionScoredEvent event, SourceOccurrenceAccess.Reference reference, byte[] wireBytes) { }
    private record ScoredRecord(TransactionScoredEvent event, byte[] bytes) { }
    private record GraphReaderFixture(DeviceGraphRelationAuthority owner, MongoClient ownerClient,
                                      DeviceGraphRelationGrant relationGrant,
                                      DeviceGraphReadGrant readGrant,
                                      com.frauddetection.enricher.graph.GraphEdgeFact edge, String principal,
                                      Instant readAt, VerifiedDeviceAdmissionIntegrationTest.Event event) { }
    private record BenchmarkSample(long elapsedNanos, PrivateDeviceDiagnosticReader.Outcome outcome) { }
    private record QueryPlan(List<String> indexes, long documentsExamined, long keysExamined, long documentsReturned,
                             boolean inMemorySort, boolean collectionScan) { }
    private record MongoClientHandle(com.mongodb.client.MongoClient writer, com.mongodb.client.MongoClient sourceReader,
                                    com.mongodb.client.MongoClient deviceReader) {
        void close() { writer.close(); sourceReader.close(); deviceReader.close(); }
    }
}
