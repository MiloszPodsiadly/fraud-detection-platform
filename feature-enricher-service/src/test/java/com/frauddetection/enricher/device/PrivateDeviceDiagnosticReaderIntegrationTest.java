package com.frauddetection.enricher.device;

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
import org.junit.jupiter.api.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Private diagnostic proof: real signed source, authenticated Mongo, published SCORED bytes and exact binding. */
@Tag("UNTRUSTED_TEST_ONLY")
class PrivateDeviceDiagnosticReaderIntegrationTest {
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

        var current = f.event(f.A, "source-after-retention", "transaction-after-retention", "customer-1", "device-new", 2,
                VerifiedDeviceAdmissionIntegrationTest.NOW);
        var currentTicket = f.stage(admission, current);
        var currentSeal = f.coverage(f.A, 2, f.A.start, VerifiedDeviceAdmissionIntegrationTest.NOW);
        admission.seal(currentTicket, currentSeal.proof(), currentSeal.payload());
        admission.commit(currentTicket);
        var currentScore = publish(current, "initial", "score-after-retention");
        admission.bindScored(currentTicket, occurrenceReader, currentScore.reference);

        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);
        String priorId = f.A.epoch().eventId(f.pseudonyms.institution(f.A.institution), DevicePseudonyms.sourceCode("BANK"),
                f.pseudonyms.event(f.A.institution, prior.id()));
        var priorObservation = f.ownerClient.getDatabase(f.database).getCollection("device_observations")
                .find(com.mongodb.client.model.Filters.eq("_id", priorId)).first();
        assertThat(priorObservation).isNotNull();
        assertThat(f.ownerClient.getDatabase(f.database).getCollection("device_observations").deleteOne(
                com.mongodb.client.model.Filters.eq("_id", priorId)).getDeletedCount()).isEqualTo(1);

        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // No stale in-process diagnostic cache.

        f.ownerClient.getDatabase(f.database).getCollection("device_observations").insertOne(priorObservation);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.EVALUATED);
        assertThat(f.ownerClient.getDatabase(f.database).getCollection("device_occurrences").deleteOne(
                com.mongodb.client.model.Filters.eq("_id", priorId)).getDeletedCount()).isEqualTo(1);

        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE);
        assertThat(diagnostic.evaluate(fact(current), currentScore.reference).outcome())
                .isEqualTo(PrivateDeviceDiagnosticReader.Outcome.UNAVAILABLE); // No stale in-process diagnostic cache.
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
    private record MongoClientHandle(com.mongodb.client.MongoClient writer, com.mongodb.client.MongoClient sourceReader,
                                    com.mongodb.client.MongoClient deviceReader) {
        void close() { writer.close(); sourceReader.close(); deviceReader.close(); }
    }
}
