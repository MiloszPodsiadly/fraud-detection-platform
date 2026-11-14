package com.frauddetection.enricher.device;

import com.frauddetection.common.events.contract.*;
import com.frauddetection.common.events.device.DeviceRiskEvidence;
import com.frauddetection.common.events.features.FraudFeatureContract;
import com.frauddetection.common.events.model.DeviceInfo;
import com.frauddetection.common.events.source.*;
import com.frauddetection.enricher.domain.EnrichedTransactionFeatures;
import com.frauddetection.enricher.mapper.TransactionEnrichedEventMapper;
import com.frauddetection.ingest.source.DeviceTestSourceReceipts;
import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.mongodb.client.model.Filters.*;
import static org.assertj.core.api.Assertions.*;

/** Real authenticated Mongo replica set, genuine receipt owner and Ed25519 test issuers; no production trust claim. */
@Tag("UNTRUSTED_TEST_ONLY")
class VerifiedDeviceAdmissionIntegrationTest {
    static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final DeviceAuthenticatedMongo MONGO = new DeviceAuthenticatedMongo();
    final KeyPair originKey = key(), deviceKey = key(), coverageKey = key();
    final Realm A = new Realm("institution-a", "epoch-a", NOW.minus(Duration.ofDays(31)));
    final Realm B = new Realm("institution-a", "epoch-b", NOW.minusSeconds(1));
    final Realm C = new Realm("institution-c", "epoch-a", NOW.minus(Duration.ofDays(31)));
    final List<Realm> realms = List.of(A, B, C);
    final DevicePseudonyms pseudonyms = new DevicePseudonyms(randomKey(), 1);
    SourceVerificationProperties origins;
    List<DeviceAssertionVerifier.Grant> grants;
    MongoClient ownerClient;
    MongoClient deviceClient;
    MongoClient sourceReaderClient;
    MongoSourceLineage owner, enricher, reader;
    SourceSignatureVerifier originVerifier;
    String database;
    int sourceReceiptCapacity = 1_000;

    @BeforeAll static void start() throws Exception { MONGO.start(); }
    @AfterAll static void stop() { MONGO.close(); }
    @BeforeEach void setUp() {
        database = "verified_device_" + UUID.randomUUID().toString().replace("-", "");
        origins = new SourceVerificationProperties(realms.stream().map(Realm::originPolicy).toList());
        grants = realms.stream().flatMap(r -> java.util.stream.Stream.of(r.grant(DeviceAssertionVerifier.Role.OBSERVATION), r.grant(DeviceAssertionVerifier.Role.COVERAGE))).toList();
        originVerifier = new SourceSignatureVerifier(origins, CLOCK);
        ownerClient = MONGO.owner();
        new MongoDeviceObservationSchema(ownerClient.getDatabase(database)).ensureIndexes();
        deviceClient = MONGO.ledgerWriter(ownerClient, database);
        owner = new MongoSourceLineage(ownerClient, database, "source_owner", MongoSourceLineage.Stage.INGEST, originVerifier, CLOCK);
        var cipher = new SourceEnrichedPublicationCipher("test-only-key", Map.of("test-only-key", Base64.getEncoder().encodeToString(randomKey())));
        SourceEnrichedBusinessIdentityMigration.migrate(ownerClient.getDatabase(database), cipher, CLOCK);
        enricher = new MongoSourceLineage(MONGO.consumer(ownerClient, database, true), database, "enricher", MongoSourceLineage.Stage.ENRICHER, originVerifier, CLOCK, cipher);
        sourceReaderClient = MONGO.consumer(ownerClient, database, false);
        reader = new MongoSourceLineage(sourceReaderClient, database, "scorer", MongoSourceLineage.Stage.SCORER, originVerifier, CLOCK);
    }
    @AfterEach void cleanup() { ownerClient.getDatabase(database).drop(); }

    @Test void actualReceiptRawEnrichedAndIndependentDeviceProofCommitAnImmutablePreEventSnapshot() {
        var admission = adapter(A);
        Event first = event(A, "source-1", "txn-1", "customer-1", "device-1", 1, NOW.minusSeconds(60));
        var one = stage(admission, first);
        Signed sealOne = coverage(A, 1, NOW.minus(Duration.ofDays(31)), first.raw.transactionTimestamp());
        admission.seal(one, sealOne.proof, sealOne.payload);
        var initial = admission.commit(one);
        assertThat(initial.status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);
        assertThat(initial.evidence().priorDevice().count()).isZero(); // Signed complete population; current event excluded.
        Event second = event(A, "source-2", "txn-2", "customer-1", "device-1", 2, NOW);
        var two = stage(admission, second);
        Signed sealTwo = coverage(A, 2, NOW.minus(Duration.ofDays(31)), NOW);
        admission.seal(two, sealTwo.proof, sealTwo.payload);
        assertThat(admission.commit(two).evidence().priorDevice().count()).isEqualTo(1);
        assertThat(admission.commit(one).evidence()).isEqualTo(initial.evidence());
        assertThat(count("device_observations")).isEqualTo(2);
        Document occurrence = rows("device_occurrences").getFirst();
        assertThat(occurrence).containsEntry("sourceIssuer", "source-issuer").containsEntry("sourceEpoch", "epoch-a")
                .containsEntry("schemaVersion", 2).containsEntry("identityVersion", 2);
        assertThat(occurrence.get("provenance", Document.class)).containsKeys("receiptId", "rawCommitment", "enrichedCommitment", "observationCommitment");
        assertThat(occurrence.toJson()).doesNotContain("device-1", "customer-1", "192.0.2.1", "signature", "originalPayload");
    }

    @Test void exactSignedReplayAfterRestartUsesOneEffectAndTheSameSnapshot() {
        Event e = event(A, "source-1", "txn-1", "customer-1", "device-1", 1, NOW);
        var first = adapter(A); var ticket = stage(first, e); Signed seal = coverage(A, 1, A.start, NOW);
        first.seal(ticket, seal.proof, seal.payload); var snapshot = first.commit(ticket).evidence();
        var restarted = new VerifiedDeviceAdmission(deviceClient, database, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), A.epoch(), reader, origins, grants, pseudonyms);
        var replay = stage(restarted, e);
        assertThat(replay.status()).isEqualTo(DeviceObservationStore.StageResult.DUPLICATE);
        restarted.seal(replay, seal.proof, seal.payload);
        assertThat(restarted.commit(replay).evidence()).isEqualTo(snapshot);
        assertThat(count("device_observation_candidates")).isEqualTo(1);
        assertThat(count("device_observations")).isEqualTo(1);
        assertThat(count("device_occurrences")).isEqualTo(1);
    }

    @Test void committedReplayRejectsMissingOccurrenceInsteadOfRecreatingHistory() {
        Event event = event(A, "missing-occurrence", "missing-occurrence-transaction", "customer-1", "missing-occurrence-device", 1, NOW);
        var first = adapter(A);
        var ticket = stage(first, event);
        Signed seal = coverage(A, 1, A.start, NOW);
        first.seal(ticket, seal.proof, seal.payload);
        assertThat(first.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);

        String id = rows("device_occurrences").getFirst().getString("_id");
        ownerClient.getDatabase(database).getCollection("device_occurrences").deleteOne(eq("_id", id));
        var restarted = adapter(A);
        var replay = stage(restarted, event);
        assertThatThrownBy(() -> restarted.commit(replay)).hasMessage("DEVICE_STORE_OCCURRENCE_MISSING");
        assertThat(ownerClient.getDatabase(database).getCollection("device_observation_candidates")
                .find(eq("_id", id)).first().getString("status")).isEqualTo("COMMITTED");
        assertThat(ownerClient.getDatabase(database).getCollection("device_occurrences").countDocuments(eq("_id", id))).isZero();
        assertThat(ownerClient.getDatabase(database).getCollection("device_history_member_anchors").countDocuments(eq("_id", id))).isEqualTo(1);
    }

    @Test void committedReplayRejectsTamperedCandidateStateAndExpiredTerminalRetention() {
        Event event = event(A, "candidate-state-replay", "candidate-state-replay-transaction", "customer-1", "candidate-state-device", 1, NOW);
        var admission = adapter(A);
        var ticket = stage(admission, event);
        Signed seal = coverage(A, 1, A.start, NOW);
        admission.seal(ticket, seal.proof, seal.payload);
        assertThat(admission.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);

        String id = rows("device_observation_candidates").getFirst().getString("_id");
        var candidates = ownerClient.getDatabase(database).getCollection("device_observation_candidates");
        Document original = candidates.find(eq("_id", id)).first();
        assertThat(original).isNotNull();

        Document tamperedState = new Document(original).append("candidateStateIntegrityCommitment", "0".repeat(64));
        candidates.replaceOne(eq("_id", id), tamperedState);
        assertThatThrownBy(() -> admission.commit(ticket)).hasMessage("DEVICE_STORE_CANDIDATE_STATE_INTEGRITY_INVALID");
        assertThat(count("device_observations")).isEqualTo(1);

        Document expired = new Document(original).append("terminalExpireAt", Date.from(NOW.minusSeconds(1)));
        expired.put("candidateStateIntegrityCommitment", DeviceRecordIntegrity.candidateState(pseudonyms, expired));
        candidates.replaceOne(eq("_id", id), expired);
        assertThatThrownBy(() -> admission.commit(ticket)).hasMessage("DEVICE_STORE_CANDIDATE_EXPIRED");
        assertThat(count("device_observations")).isEqualTo(1);
        assertThat(count("device_occurrences")).isEqualTo(1);
    }

    @Test void committedReplayRejectsExpiredOccurrenceEvenWhenItsIntegrityIsValid() {
        Event event = event(A, "expired-occurrence", "expired-occurrence-transaction", "customer-1", "expired-occurrence-device", 1, NOW);
        var admission = adapter(A);
        var ticket = stage(admission, event);
        Signed seal = coverage(A, 1, A.start, NOW);
        admission.seal(ticket, seal.proof, seal.payload);
        assertThat(admission.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);

        var occurrences = ownerClient.getDatabase(database).getCollection("device_occurrences");
        Document occurrence = rows("device_occurrences").getFirst();
        occurrence.put("expireAt", Date.from(NOW.minusSeconds(1)));
        occurrence.put("membershipIntegrityCommitment", DeviceRecordIntegrity.membership(pseudonyms, occurrence));
        occurrence.put("stageACommitment", DeviceSnapshotVersion.commitment(occurrence));
        occurrence.put("snapshotIntegrityCommitment", DeviceRecordIntegrity.snapshot(pseudonyms, occurrence));
        occurrences.replaceOne(eq("_id", occurrence.getString("_id")), occurrence);

        assertThatThrownBy(() -> admission.commit(ticket)).hasMessage("DEVICE_STORE_OCCURRENCE_EXPIRED");
        assertThat(count("device_observations")).isEqualTo(1);
        assertThat(count("device_occurrences")).isEqualTo(1);
    }

    @Test void conflictingSignedEventOrReusedSequenceCannotReplaceCommittedEvidence() {
        Event e = event(A, "source-1", "txn-1", "customer-1", "device-1", 1, NOW.minusSeconds(1));
        var a = adapter(A); var ticket = stage(a, e); Signed seal = coverage(A, 1, A.start, e.raw.transactionTimestamp());
        a.seal(ticket, seal.proof, seal.payload); var snapshot = a.commit(ticket).evidence();
        Map<String, Object> changed = observation(e); changed.put("sourceSequence", 2);
        Signed conflict = signObservation(e, changed);
        assertThatThrownBy(() -> a.stage(e.sourceProof, e.sourcePayload, conflict.proof, conflict.payload, e.raw, e.enriched))
                .hasMessage("DEVICE_STORE_IDENTITY_CONFLICT");
        Event reused = event(A, "source-2", "txn-2", "customer-1", "device-2", 1, NOW);
        assertThatThrownBy(() -> stage(a, reused)).hasMessage("DEVICE_STORE_IDENTITY_CONFLICT");
        assertThat(a.commit(ticket).evidence()).isEqualTo(snapshot);
        assertThat(count("device_occurrences")).isEqualTo(1);
    }

    @Test void epochResetReusesEventKeyAndSequenceWithoutInheritingCoverageHistoryOrRevocation() {
        Event old = event(A, "reused-event", "reused-txn", "customer-1", "device-1", 1, NOW);
        var a = adapter(A); var oldTicket = stage(a, old); Signed oldSeal = coverage(A, 1, A.start, NOW);
        a.seal(oldTicket, oldSeal.proof, oldSeal.payload); a.commit(oldTicket);
        Event reset = event(B, "reused-event", "reused-txn", "customer-1", "device-1", 1, NOW);
        var b = adapter(B); var newTicket = stage(b, reset);
        assertThat(b.commit(newTicket).status()).isEqualTo(DeviceObservationStore.CommitStatus.WAITING_FOR_SEAL);
        assertThatThrownBy(() -> b.seal(newTicket, oldSeal.proof, oldSeal.payload)).hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
        Signed freshShortSeal = coverage(B, 1, B.start, NOW);
        b.seal(newTicket, freshShortSeal.proof, freshShortSeal.payload);
        assertThat(b.commit(newTicket).evidence().priorDevice().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(b.commit(newTicket).evidence().priorDevice().count()).isNull();
        var docs = rows("device_observation_candidates");
        assertThat(docs).hasSize(2);
        assertThat(docs.stream().map(d -> d.getString("sourceEvent")).distinct().count()).isEqualTo(1);
        assertThat(docs.stream().map(d -> d.getString("_id")).distinct().count()).isEqualTo(2);
        var administration = new MongoDeviceObservationStore(ownerClient, database, CLOCK, A.epoch(), DeviceStoreFixtures.TRUST_STRUCTURAL_TEST_INPUTS);
        administration.revokeDeviceFromOperationalHistory(pseudonyms.institution(A.institution), DevicePseudonyms.sourceCode("BANK"), 1, pseudonyms.device(A.institution, "device-1"));
        assertThat(b.commit(newTicket).status()).isEqualTo(DeviceObservationStore.CommitStatus.EXISTING);
        assertThat(rows("device_observations")).hasSize(1);
        assertThat(rows("device_observations").getFirst().getString("sourceEpoch")).isEqualTo("epoch-b");
    }

    @Test void operationalRevocationRemovesTheSealedMembershipAnchorWithItsObservation() {
        Event event = event(A, "source-revoke-anchor", "transaction-revoke-anchor", "customer-1", "device-revoke-anchor", 1, NOW);
        var admission = adapter(A);
        var ticket = stage(admission, event);
        Signed seal = coverage(A, 1, A.start, NOW);
        admission.seal(ticket, seal.proof, seal.payload);
        admission.commit(ticket);

        String institution = pseudonyms.institution(A.institution);
        String sourceCode = DevicePseudonyms.sourceCode("BANK");
        String device = pseudonyms.device(A.institution, "device-revoke-anchor");
        assertThat(count("device_history_member_anchors")).isEqualTo(1);
        var operationalStore = new MongoDeviceObservationStore(deviceClient, database, CLOCK, A.epoch(),
                DeviceStoreFixtures.TRUST_STRUCTURAL_TEST_INPUTS);
        operationalStore.revokeDeviceFromOperationalHistory(institution, sourceCode, 1, device);

        assertThat(count("device_history_member_anchors")).isZero();
        assertThat(count("device_observations")).isZero();
        assertThat(ownerClient.getDatabase(database).getCollection("device_source_coverage")
                .find(eq("_id", A.epoch().scopeId(institution, sourceCode))).first().getBoolean("invalidated")).isTrue();
    }

    @Test void gapAndPendingPredecessorNeverCreateFalseHistory() {
        var a = adapter(A);
        Event one = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW.minusSeconds(2));
        Event three = event(A, "e3", "t3", "customer-1", "device-1", 3, NOW);
        var first = stage(a, one); var third = stage(a, three);
        Signed gap = coverage(A, 3, A.start, NOW);
        assertThatThrownBy(() -> a.seal(third, gap.proof, gap.payload)).hasMessage("DEVICE_STORE_SOURCE_SEQUENCE_GAP");
        assertThat(a.commit(third).status()).isEqualTo(DeviceObservationStore.CommitStatus.WAITING_FOR_SEAL);
        assertThat(count("device_occurrences")).isZero();
        Event two = event(A, "e2", "t2", "customer-1", "device-1", 2, NOW.minusSeconds(1));
        var second = stage(a, two); Signed complete = coverage(A, 3, A.start, NOW);
        a.seal(third, complete.proof, complete.payload);
        assertThat(a.commit(third).status()).isEqualTo(DeviceObservationStore.CommitStatus.WAITING_FOR_EARLIER_EVENT);
        a.commit(first); a.commit(second);
        assertThat(a.commit(third).evidence().priorDevice().count()).isEqualTo(2);
    }

    @Test void unknownPopulationAndUnmatchedManifestFailClosedAndShortCoverageIsUnavailable() {
        var a = adapter(A); Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        var ticket = stage(a, e); Signed valid = coverage(A, 1, NOW.minusSeconds(1), NOW);
        Map<String, Object> body = new LinkedHashMap<>(JSON.readValue(valid.payload, Map.class)); body.put("populationId", "unknown-population");
        Signed unknown = signCoverage(A, body);
        assertThatThrownBy(() -> a.seal(ticket, unknown.proof, unknown.payload)).hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
        body.put("populationId", A.population().id()); body.put("coverageCommitment", "8".repeat(64));
        Signed wrongManifest = signCoverage(A, body);
        assertThatThrownBy(() -> a.seal(ticket, wrongManifest.proof, wrongManifest.payload)).hasMessage("DEVICE_STORE_SEAL_MANIFEST_CONFLICT");
        assertThat(a.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.WAITING_FOR_SEAL);
        a.seal(ticket, valid.proof, valid.payload);
        assertThat(a.commit(ticket).evidence().priorDevice().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(a.commit(ticket).evidence().priorDevice().count()).isNull();
    }

    @Test void crossInstitutionCustomerRawAndReceiptInjectionAreDeniedBeforeStaging() {
        Event first = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        Event other = event(C, "e1", "t1", "customer-1", "device-1", 1, NOW);
        var a = adapter(A); var c = adapter(C);
        Signed wrongTenant = signObservation(first, observation(first));
        assertThatThrownBy(() -> c.stage(other.sourceProof, other.sourcePayload, wrongTenant.proof, wrongTenant.payload, other.raw, other.enriched))
                .hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
        for (String field : List.of("customerToken", "receiptId", "rawCommitment", "enrichedCommitment", "deviceToken")) {
            Map<String, Object> claim = observation(first); claim.put(field, "7".repeat(64)); Signed forged = signObservation(first, claim);
            assertThatThrownBy(() -> a.stage(first.sourceProof, first.sourcePayload, forged.proof, forged.payload, first.raw, first.enriched))
                    .hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
        }
        assertThat(count("device_observation_candidates")).isZero();
    }

    @Test void concurrentVerifiedWorkersSealAndCommitOneOccurrence() throws Exception {
        Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        var first = adapter(A); var second = adapter(A);
        var one = stage(first, e); var two = stage(second, e); Signed seal = coverage(A, 1, A.start, NOW);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            var results = workers.invokeAll(List.<Callable<DeviceObservationStore.CommitResult>>of(
                    () -> { first.seal(one, seal.proof, seal.payload); return first.commit(one); },
                    () -> { second.seal(two, seal.proof, seal.payload); return second.commit(two); }));
            assertThat(results.get(0).get().evidence()).isEqualTo(results.get(1).get().evidence());
        }
        assertThat(count("device_occurrences")).isEqualTo(1);
        assertThat(count("device_observations")).isEqualTo(1);
        assertThatThrownBy(() -> first.commit(two)).hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
    }

    @Test void historicalReceiptRevocationMakesTheNextWindowUnavailableAndCurrentReceiptRevocationDeniesReplay() {
        var a = adapter(A); Event one = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW.minusSeconds(1));
        var first = stage(a, one); Signed sealOne = coverage(A, 1, A.start, one.raw.transactionTimestamp());
        a.seal(first, sealOne.proof, sealOne.payload); a.commit(first);
        Event two = event(A, "e2", "t2", "customer-1", "device-1", 2, NOW);
        var second = stage(a, two); Signed sealTwo = coverage(A, 2, A.start, NOW); a.seal(second, sealTwo.proof, sealTwo.payload);
        ownerClient.getDatabase(database).getCollection("source_admission_identities")
                .updateOne(eq("_id", SourceReceiptIdentity.identity(one.origin.scope())), new Document("$set", new Document("state", "CONFLICT")));
        var result = a.commit(second).evidence();
        assertThat(result.priorDevice().state()).isEqualTo(DeviceRiskEvidence.State.UNAVAILABLE);
        assertThat(result.priorDevice().count()).isNull();
        assertThatThrownBy(() -> a.commit(first)).hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
    }

    @Test void restartBetweenStageSealAndCommitNeverInventsCoverageOrASecondEffect() {
        Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        stage(adapter(A), e);
        var recovered = adapter(A); var ticket = stage(recovered, e);
        assertThat(recovered.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.WAITING_FOR_SEAL);
        Signed seal = coverage(A, 1, A.start, NOW); recovered.seal(ticket, seal.proof, seal.payload);
        var afterSeal = adapter(A); var replay = stage(afterSeal, e);
        assertThat(afterSeal.commit(replay).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED);
        assertThat(afterSeal.commit(replay).status()).isEqualTo(DeviceObservationStore.CommitStatus.EXISTING);
        assertThat(count("device_observations")).isEqualTo(1);
    }

    @Test void realUnknownCommitAcknowledgementRetriesTheSameTransactionAndKeepsOneEffect() {
        Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        var a = adapter(A); var ticket = stage(a, e); Signed seal = coverage(A, 1, A.start, NOW);
        a.seal(ticket, seal.proof, seal.payload);
        var admin = ownerClient.getDatabase("admin");
        Document before = admin.runCommand(new Document("configureFailPoint", "failCommand").append("mode", new Document("times", 1))
                .append("data", new Document("failCommands", List.of("commitTransaction")).append("appName", "device-ledger-test")
                        .append("errorCode", 91).append("errorLabels", List.of("UnknownTransactionCommitResult"))));
        try { assertThat(a.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.COMMITTED); }
        finally {
            Document after = admin.runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
            assertThat(((Number) after.get("count")).longValue()).isGreaterThan(((Number) before.get("count")).longValue());
        }
        assertThat(a.commit(ticket).status()).isEqualTo(DeviceObservationStore.CommitStatus.EXISTING);
        assertThat(count("device_occurrences")).isEqualTo(1); assertThat(count("device_observations")).isEqualTo(1);
    }

    @Test void schemaAndEveryIdentityIndexRequireExplicitEpochAndNeverRepairLegacyRows() {
        var a = adapter(A); Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW); stage(a, e);
        var db = ownerClient.getDatabase(database); var candidates = db.getCollection("device_observation_candidates");
        for (Document index : candidates.listIndexes()) {
            if (index.getBoolean("unique", false)) {
                assertThat(index.get("key", Document.class)).containsKeys("sourceIssuer", "sourceEpoch", "identityVersion", "institution", "source");
            }
        }
        var row = candidates.find().first();
        assertThatThrownBy(() -> candidates.updateOne(eq("_id", row.getString("_id")), new Document("$unset", new Document("sourceEpoch", ""))))
                .isInstanceOf(com.mongodb.MongoWriteException.class);
        candidates.updateOne(eq("_id", row.getString("_id")), new Document("$unset", new Document("sourceEpoch", "")),
                new com.mongodb.client.model.UpdateOptions().bypassDocumentValidation(true));
        assertThatThrownBy(() -> adapter(A)).hasMessage("DEVICE_STORE_SCHEMA_INCOMPATIBLE");
        assertThat(candidates.find().first()).doesNotContainKey("sourceEpoch");
        assertThat(count("device_occurrences")).isZero();
    }

    @Test void revocationRacingWithVerifiedStageCannotLeaveUnrevokedPendingWorkOrRestoreHistory() throws Exception {
        Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        var admission = adapter(A);
        var administration = new MongoDeviceObservationStore(ownerClient, database, CLOCK, A.epoch(), DeviceStoreFixtures.TRUST_STRUCTURAL_TEST_INPUTS);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            var results = workers.invokeAll(List.<Callable<VerifiedDeviceAdmission.Ticket>>of(
                    () -> { try { return stage(admission, e); } catch (IllegalStateException denied) {
                        assertThat(denied.getMessage()).isIn("DEVICE_STORE_DEVICE_OPERATIONALLY_REVOKED", "DEVICE_STORE_UNAVAILABLE_STAGE"); return null;
                    } },
                    () -> { administration.revokeDeviceFromOperationalHistory(pseudonyms.institution(A.institution), DevicePseudonyms.sourceCode("BANK"), 1,
                            pseudonyms.device(A.institution, "device-1")); return null; }));
            var ticket = results.getFirst().get(); results.getLast().get();
            if (ticket != null) { assertThatThrownBy(() -> admission.commit(ticket)).hasMessage("DEVICE_STORE_DEVICE_OPERATIONALLY_REVOKED"); }
        }
        assertThat(rows("device_observation_candidates")).allSatisfy(row -> assertThat(row.getString("status")).isEqualTo("QUARANTINED"));
        assertThat(count("device_occurrences")).isZero(); assertThat(count("device_observations")).isZero();
    }

    @Test void runtimePrincipalsCannotWriteSourceReceiptsModifySchemaOrReadEachOthersCollections() {
        adapter(A); event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        assertThatThrownBy(() -> deviceClient.getDatabase(database).getCollection("source_admission_receipts").find().first())
                .isInstanceOf(com.mongodb.MongoCommandException.class);
        assertThatThrownBy(() -> deviceClient.getDatabase(database).getCollection("source_admission_receipts")
                .updateOne(new Document(), new Document("$set", new Document("policyVersion", "forged"))))
                .isInstanceOf(com.mongodb.MongoCommandException.class);
        assertThatThrownBy(() -> deviceClient.getDatabase(database).runCommand(new Document("collMod", "device_observation_candidates")
                .append("validator", new Document())))
                .isInstanceOf(com.mongodb.MongoCommandException.class);
        assertThatThrownBy(() -> sourceReaderClient.getDatabase(database).getCollection("device_occurrences").find().first())
                .isInstanceOf(com.mongodb.MongoCommandException.class);
    }

    @Test void aRevokedCoverageKeyCannotBeBypassedBySkippingSealOnRestart() {
        Event e = event(A, "e1", "t1", "customer-1", "device-1", 1, NOW);
        var original = adapter(A); var first = stage(original, e); Signed seal = coverage(A, 1, A.start, NOW);
        original.seal(first, seal.proof, seal.payload); original.commit(first);
        List<DeviceAssertionVerifier.Grant> revoked = grants.stream().map(g -> {
            if (g.role() != DeviceAssertionVerifier.Role.COVERAGE) { return g; }
            var k = g.key();
            var key = new SourceVerificationProperties.TrustedKey(k.issuer(), k.institution(), k.source(), k.sourceEpoch(), k.signingKeyVersion(),
                    k.algorithm(), k.publicKeyBase64(), k.notBefore(), k.notAfter(), true, k.purpose(), k.audience(), k.policyVersion());
            return new DeviceAssertionVerifier.Grant(g.role(), key, g.sourceScope(), g.population(), g.pseudonymKeyVersion(), g.bindingAuthority(), g.providerBasis());
        }).toList();
        var restarted = new VerifiedDeviceAdmission(deviceClient, database, CLOCK, A.epoch(), reader, origins, revoked, pseudonyms);
        var replay = stage(restarted, e);
        assertThatThrownBy(() -> restarted.commit(replay)).hasMessage("DEVICE_STORE_COVERAGE_AUTHORITY_REVOKED");
        assertThatThrownBy(() -> restarted.seal(replay, seal.proof, seal.payload)).hasMessage("DEVICE_VERIFIED_ADMISSION_DENIED");
        assertThat(count("device_occurrences")).isEqualTo(1);
    }

    VerifiedDeviceAdmission adapter(Realm realm) { return new VerifiedDeviceAdmission(deviceClient, database, CLOCK, realm.epoch(), reader, origins, grants, pseudonyms); }
    void useBenchmarkLedgerTimeout(Duration timeout) {
        deviceClient.close();
        deviceClient = MONGO.ledgerWriterWithTimeout(database, timeout);
    }
    VerifiedDeviceAdmission.Ticket stage(VerifiedDeviceAdmission adapter, Event event) {
        Signed observation = signObservation(event, observation(event));
        return adapter.stage(event.sourceProof, event.sourcePayload, observation.proof, observation.payload, event.raw, event.enriched);
    }
    Event event(Realm realm, String id, String transaction, String customer, String device, long sequence, Instant time) {
        TransactionRawEvent raw = new TransactionRawEvent("raw-" + realm.institution + '-' + realm.epoch + '-' + id, transaction, "corr-" + id, customer, "account", "instrument",
                NOW, time, null, null, new DeviceInfo(device, "caller-fingerprint", "192.0.2.1", "test-agent", "web", "browser", true, true, true, Map.of()),
                null, null, "PURCHASE", "CHIP", "UNTRUSTED_CALLER_LABEL", "trace", Map.of());
        byte[] payload = JSON.writeValueAsBytes(raw);
        SourceScope scope = scope(realm.originPolicy(), id, time, payload);
        byte[] proof = sign(scope, payload, originKey);
        var origin = (SourceSignatureVerifier.Verified) originVerifier.verify(proof, payload, new SourceSignatureVerifier.Context(DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest"));
        DeviceTestSourceReceipts.admit(ownerClient, database, CLOCK, originVerifier, owner, origin, raw, sourceReceiptCapacity);
        Map<String, Object> features = new LinkedHashMap<>(); FraudFeatureContract.JAVA_ENRICHED_FEATURE_NAMES.forEach(name -> features.put(name, 0));
        var enriched = new TransactionEnrichedEventMapper().toAttestedEvent(raw, new EnrichedTransactionFeatures(features));
        var publication = enricher.prepareEnrichedPublication(raw, enriched);
        assertThat(publication.authority().state()).isEqualTo(SourceLineage.State.VERIFIED);
        var claim = enricher.claimEnrichedPublication(raw, publication.event());
        // A synthetic feature-history effect is acknowledged through the real fenced API; no Kafka/Redis delivery is claimed here.
        assertThat(enricher.markEnrichedHistoryApplied(claim).state()).isEqualTo(SourceLineage.State.VERIFIED);
        assertThat(reader.enriched(publication.event()).state()).isEqualTo(SourceLineage.State.VERIFIED);
        return new Event(realm, id, sequence, raw, publication.event(), origin, proof, payload);
    }
    Map<String, Object> observation(Event e) {
        Map<String, Object> body = base(e.realm); body.put("sourceEventId", e.id);
        body.put("transactionKey", pseudonyms.transaction(e.realm.institution, e.raw.customerId(), e.raw.transactionId()));
        body.put("receiptId", SourceReceiptIdentity.identity(e.origin.scope())); body.put("rawCommitment", SourceEventCommitment.raw(e.raw));
        body.put("enrichedCommitment", SourceEventCommitment.enriched(e.enriched)); body.put("customerToken", pseudonyms.customer(e.realm.institution, e.raw.customerId()));
        body.put("deviceToken", pseudonyms.device(e.realm.institution, e.raw.deviceInfo().deviceId())); body.put("sourceSequence", e.sequence);
        body.put("eventTime", e.raw.transactionTimestamp().toString()); body.put("observedAt", e.raw.transactionTimestamp().toString());
        body.put("bindingAuthority", "institution-binding-v1"); body.put("providerBasis", "institution-provider-v1"); return body;
    }
    Signed signObservation(Event e, Map<String, Object> body) {
        byte[] payload = JSON.writeValueAsBytes(body);
        return new Signed(sign(scope(e.realm.grant(DeviceAssertionVerifier.Role.OBSERVATION).key(), e.id, e.raw.transactionTimestamp(), payload), payload, deviceKey), payload);
    }
    Signed coverage(Realm realm, long through, Instant start, Instant watermark) {
        String institution = pseudonyms.institution(realm.institution), source = DevicePseudonyms.sourceCode("BANK");
        Document progress = ownerClient.getDatabase(database).getCollection("device_source_coverage").find(eq("_id", realm.epoch().scopeId(institution, source))).first();
        long previous = progress.getLong("sealedSequence"), generation = progress.getLong("coverageGeneration") + 1;
        var proof = new DeviceObservationStore.CoverageProof(1, previous + 1, generation, progress.getString("coverageCommitment"), "0".repeat(64), "0".repeat(64),
                DeviceAssertionVerifier.populationCommitment(realm.population()), DeviceStoreFixtures.coverageAuthority(watermark)); // Manifest prototype only, never admitted.
        var prototype = new DeviceObservationStore.CoverageSeal(institution, source, through, start, watermark, 1, proof);
        List<Document> batch = ownerClient.getDatabase(database).getCollection("device_observation_candidates")
                .find(and(realm.epoch().filter(institution, source), gt("sequence", previous), lte("sequence", through))).sort(new Document("sequence", 1)).into(new ArrayList<>());
        Map<String, Object> body = base(realm); body.put("coverageId", "coverage-" + generation);
        body.put("inclusionRule", realm.population().inclusionRule()); body.put("exclusionRule", realm.population().exclusionRule());
        body.put("sequenceOrigin", 1); body.put("fromSequence", previous + 1); body.put("throughSequence", through);
        body.put("coverageStart", start.toString()); body.put("watermark", watermark.toString()); body.put("coverageGeneration", generation);
        body.put("previousCoverageCommitment", progress.getString("coverageCommitment"));
        body.put("coverageCommitment", DeviceCoverageCommitment.compute(realm.epoch(), prototype, batch));
        return signCoverage(realm, body);
    }
    Signed signCoverage(Realm realm, Map<String, Object> body) {
        byte[] payload = JSON.writeValueAsBytes(body);
        return new Signed(sign(scope(realm.grant(DeviceAssertionVerifier.Role.COVERAGE).key(), (String) body.get("coverageId"),
                Instant.parse((String) body.get("watermark")), payload), payload, coverageKey), payload);
    }
    Map<String, Object> base(Realm realm) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("assertionVersion", 1); body.put("sourceIssuer", "source-issuer");
        body.put("institution", realm.institution); body.put("source", "BANK"); body.put("sourceEpoch", realm.epoch);
        body.put("populationId", realm.population().id()); body.put("populationDefinitionVersion", realm.population().definitionVersion());
        body.put("populationDefinitionCommitment", realm.population().definitionCommitment()); body.put("policyVersion", "device-policy-v1"); body.put("pseudonymKeyVersion", 1); return body;
    }
    List<Document> rows(String collection) { return ownerClient.getDatabase(database).getCollection(collection).find().into(new ArrayList<>()); }
    long count(String collection) { return ownerClient.getDatabase(database).getCollection(collection).countDocuments(); }
    final class Realm {
        final String institution, epoch; final Instant start;
        Realm(String institution, String epoch, Instant start) { this.institution = institution; this.epoch = epoch; this.start = start; }
        DeviceEpoch epoch() { return new DeviceEpoch("source-issuer", epoch); }
        DeviceAssertionVerifier.Population population() { return new DeviceAssertionVerifier.Population("institution-device-events", "definition-v1",
                DeviceEpoch.digest("test-population", "all-device-observations"), "ALL_INSTITUTION_DEVICE_OBSERVATIONS", "NONE", 1, start); }
        SourceVerificationProperties.TrustedKey originPolicy() { return trusted("source-issuer", "origin-" + institution, DeviceAssertionVerifier.TRANSACTION_PURPOSE, "transaction-ingest", originKey, "origin-policy-v1"); }
        DeviceAssertionVerifier.Grant grant(DeviceAssertionVerifier.Role role) {
            return new DeviceAssertionVerifier.Grant(role, trusted(role == DeviceAssertionVerifier.Role.OBSERVATION ? "device-issuer" : "coverage-issuer",
                    role.name() + '-' + institution, role.purpose, DeviceAssertionVerifier.AUDIENCE, role == DeviceAssertionVerifier.Role.OBSERVATION ? deviceKey : coverageKey, "device-policy-v1"),
                    new DeviceAssertions.Scope("source-issuer", institution, "BANK", epoch), population(), 1, "institution-binding-v1", "institution-provider-v1");
        }
        SourceVerificationProperties.TrustedKey trusted(String issuer, String version, String purpose, String audience, KeyPair key, String policy) {
            return new SourceVerificationProperties.TrustedKey(issuer, institution, "BANK", epoch, version, "Ed25519", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
                    NOW.minus(Duration.ofDays(90)), NOW.plus(Duration.ofDays(1)), false, purpose, audience, policy);
        }
    }
    record Event(Realm realm, String id, long sequence, TransactionRawEvent raw, TransactionEnrichedEvent enriched,
                 SourceSignatureVerifier.Verified origin, byte[] sourceProof, byte[] sourcePayload) { }
    record Signed(byte[] proof, byte[] payload) { }
    static SourceScope scope(SourceVerificationProperties.TrustedKey key, String event, Instant time, byte[] payload) {
        return new SourceScope(key.issuer(), key.institution(), key.source(), key.sourceEpoch(), key.signingKeyVersion(), event, time,
                key.purpose(), key.audience(), SourceSignedInput.payloadCommitment(payload));
    }
    static byte[] sign(SourceScope s, byte[] payload, KeyPair key) {
        try {
            Signature signature = Signature.getInstance("Ed25519"); signature.initSign(key.getPrivate()); signature.update(SourceSignedInput.encode(s, payload));
            Map<String, Object> proof = new LinkedHashMap<>(); proof.put("protocolVersion", 1); proof.put("algorithm", "Ed25519"); proof.put("issuer", s.issuer());
            proof.put("institution", s.institution()); proof.put("source", s.source()); proof.put("sourceEpoch", s.sourceEpoch()); proof.put("signingKeyVersion", s.signingKeyVersion());
            proof.put("sourceEventId", s.sourceEventId()); proof.put("eventTime", s.eventTime().toString()); proof.put("purpose", s.purpose()); proof.put("audience", s.audience());
            proof.put("payloadCommitment", s.payloadCommitment()); proof.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())); return JSON.writeValueAsBytes(proof);
        } catch (Exception unavailable) { throw new IllegalStateException("TEST_SIGNATURE_FAILED"); }
    }
    static KeyPair key() { try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); } catch (Exception e) { throw new IllegalStateException(e); } }
    static byte[] randomKey() { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }
}
