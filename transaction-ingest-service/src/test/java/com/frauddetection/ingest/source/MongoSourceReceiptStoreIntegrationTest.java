package com.frauddetection.ingest.source;

import com.frauddetection.common.events.source.SourceVerificationProperties;
import com.frauddetection.common.events.source.SourceSignatureVerifier;


import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.frauddetection.ingest.source.MongoSourceReceiptSchema.*;
import static com.frauddetection.ingest.source.SourceReceiptStore.Status.*;
import static com.frauddetection.ingest.source.TestSourceIssuer.*;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MongoSourceReceiptStoreIntegrationTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    private static final SourceReceiptStore.Limits LIMITS_POLICY = new SourceReceiptStore.Limits(
            Duration.ofMinutes(5), Duration.ofMinutes(10), 100);
    private MongoClient client;
    private MongoDatabase database;
    private String databaseName;
    private MutableClock clock;
    private SourceSignatureVerifier verifier;
    private MongoSourceReceiptStore store;

    @BeforeEach void setup() {
        client = newClient();
        databaseName = "source_" + UUID.randomUUID().toString().replace("-", "");
        database = client.getDatabase(databaseName);
        clock = new MutableClock(NOW);
        verifier = verifier(policy(0, false), policy(1, false));
        store = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier);
    }

    @AfterEach void close() { client.close(); }

    @Test void maximumLengthSignedScopeKeepsReceiptMetadataBounded() throws Exception {
        var claims = Map.<String, Object>of("issuer", "a".repeat(128), "institution", "b".repeat(128), "source", "c".repeat(128),
                "sourceEpoch", "d".repeat(128), "signingKeyVersion", "e".repeat(128), "sourceEventId", "f".repeat(128),
                "purpose", "g".repeat(128), "audience", "h".repeat(128));
        var key = new SourceVerificationProperties.TrustedKey((String) claims.get("issuer"), (String) claims.get("institution"),
                (String) claims.get("source"), (String) claims.get("sourceEpoch"), (String) claims.get("signingKeyVersion"),
                "Ed25519", publicKey(0), NOW.minusSeconds(3_600), NOW.plusSeconds(3_600), false,
                (String) claims.get("purpose"), (String) claims.get("audience"), "p".repeat(128));
        var maximumVerifier = verifier(key);
        var evidence = (SourceSignatureVerifier.Verified) maximumVerifier.verify(proof(PAYLOAD, claims, 0), PAYLOAD,
                new SourceSignatureVerifier.Context(key.purpose(), key.audience()));
        var bounded = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, maximumVerifier);
        assertThat(bounded.admit(evidence).status()).isEqualTo(ADMITTED);
        int size;
        try (var output = new org.bson.io.BasicOutputBuffer(); var writer = new org.bson.BsonBinaryWriter(output)) {
            new org.bson.codecs.DocumentCodec().encode(writer, database.getCollection(RECEIPTS).find().first(),
                    org.bson.codecs.EncoderContext.builder().isEncodingCollectibleDocument(true).build());
            size = output.getSize();
        }
        assertThat(size).isLessThanOrEqualTo(4_096);
        System.out.println("SOURCE_RECEIPT_MAX_SCOPE_BSON_BYTES " + size);
    }

    @Test void understatedGlobalCounterCannotAdmitBeyondTheLifetimeCapacity() throws Exception {
        String cappedDatabase = databaseName + "_cap";
        var capped = new MongoSourceReceiptStore(client, cappedDatabase, clock,
                new SourceReceiptStore.Limits(Duration.ofMinutes(5), Duration.ofMinutes(10), 1), verifier);
        assertThat(capped.admit(verified(PAYLOAD, Map.of(), 0)).status()).isEqualTo(ADMITTED);
        client.getDatabase(cappedDatabase).getCollection(LIMITS).updateOne(eq("_id", "capacity"), com.mongodb.client.model.Updates.set("count", 0));
        assertThat(capped.admit(verified(PAYLOAD, Map.of("sourceEventId", "another-source-event"), 0)).status()).isEqualTo(UNRESOLVED);
        assertThat(client.getDatabase(cappedDatabase).getCollection(IDENTITIES).countDocuments()).isEqualTo(1);
        assertThat(client.getDatabase(cappedDatabase).getCollection(RECEIPTS).countDocuments()).isEqualTo(1);
        clock.instant = NOW.plusSeconds(600);
        assertThat(capped.compactExpired(1)).isEqualTo(1);
        assertThat(capped.admit(verified(PAYLOAD, Map.of("sourceEventId", "new-source-after-compaction", "eventTime", clock.instant.toString()), 0)).status())
                .isEqualTo(UNRESOLVED);
        assertThat(client.getDatabase(cappedDatabase).getCollection(IDENTITIES).countDocuments()).isEqualTo(1);
        assertThat(client.getDatabase(cappedDatabase).getCollection(RECEIPTS).countDocuments()).isZero();
    }

    @Test void exactReplayHasOneImmutableReceiptAndNoSensitivePayloadCopy() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        var admitted = store.admit(evidence);
        assertThat(admitted.status()).isEqualTo(ADMITTED);
        Document original = database.getCollection(RECEIPTS).find().first();
        clock.instant = NOW.plusSeconds(1);
        assertThat(store.admit(verified(PAYLOAD, Map.of(), 0)).status()).isEqualTo(EXISTING);
        assertThat(store.readOnly().lookup(evidence, admitted.receipt().id()).status()).isEqualTo(ADMITTED);
        assertThat(database.getCollection(RECEIPTS).find().first()).isEqualTo(original);
        assertCounts(1, 1);
        assertThat(original.toJson()).doesNotContain("txn-source-1", "42.00", "signature", publicKey(0));
        assertThat(original.toJson().length()).isLessThan(4_096);
    }

    @Test void simultaneousDuplicatesLinearizeAtOneMajorityCommit() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        CyclicBarrier barrier = new CyclicBarrier(6);
        List<SourceReceiptStore.Result> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<java.util.concurrent.Future<SourceReceiptStore.Result>>();
            for (int i = 0; i < 6; i++) {
                tasks.add(executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return store.admit(evidence); }));
            }
            for (var task : tasks) { outcomes.add(task.get(30, TimeUnit.SECONDS)); }
        }
        assertThat(outcomes).filteredOn(result -> result.status() == ADMITTED).hasSize(1);
        assertThat(outcomes).allSatisfy(result -> assertThat(result.status()).isIn(ADMITTED, EXISTING, UNRESOLVED));
        assertThat(store.admit(evidence).status()).isEqualTo(EXISTING);
        assertCounts(1, 1);
    }

    @Test void changedSignedBytesFenceTheIdentityWithoutOverwritingOriginalEvidence() throws Exception {
        var first = verified(PAYLOAD, Map.of(), 0);
        var admission = store.admit(first);
        Document original = database.getCollection(RECEIPTS).find().first();
        byte[] changed = "{\"transactionId\":\"txn-source-1\",\"amount\":\"43.00\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(store.admit(verified(changed, Map.of(), 0)).status()).isEqualTo(CONFLICT);
        assertThat(store.admit(first).status()).isEqualTo(CONFLICT);
        assertThat(store.readOnly().lookup(first, admission.receipt().id()).status()).isEqualTo(CONFLICT);
        assertThat(database.getCollection(RECEIPTS).find().first()).isEqualTo(original);
        assertCounts(1, 1);
    }

    @Test void keyRotationCannotReissueTheSameSourceEventAsAnotherIdentity() throws Exception {
        assertThat(store.admit(verified(PAYLOAD, Map.of(), 0)).status()).isEqualTo(ADMITTED);
        assertThat(store.admit(verified(PAYLOAD, Map.of("signingKeyVersion", "key-2"), 1)).status()).isEqualTo(CONFLICT);
        assertCounts(1, 1);
    }

    @Test void sameTransactionAndOpaqueEventIdRemainIsolatedAcrossInstitutionsAndEpochs() throws Exception {
        var otherInstitution = copyPolicy("issuer-b", "institution-b", "epoch-a", publicKey(0));
        var otherEpoch = copyPolicy(ISSUER, "institution-a", "epoch-b", publicKey(0));
        verifier = verifier(policy(0, false), otherInstitution, otherEpoch);
        store = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier);
        var first = verified(PAYLOAD, Map.of(), 0);
        var second = verified(PAYLOAD, Map.of("issuer", "issuer-b", "institution", "institution-b"), 0);
        var third = verified(PAYLOAD, Map.of("sourceEpoch", "epoch-b"), 0);
        var initial = store.admit(first);
        assertThat(store.admit(second).status()).isEqualTo(ADMITTED);
        assertThat(store.admit(third).status()).isEqualTo(ADMITTED);
        assertThat(store.admit(verified(PAYLOAD, Map.of("sourceEventId", "another-event"), 0)).status()).isEqualTo(ADMITTED);
        assertThat(store.readOnly().lookup(second, initial.receipt().id()).status()).isEqualTo(UNVERIFIED);
        assertThat(store.readOnly().lookup(third, initial.receipt().id()).status()).isEqualTo(UNVERIFIED);
        assertCounts(4, 4);
    }

    @Test void unknownCommitAcknowledgmentsNeverReturnSuccessAndReconcileFromRealMongoFacts() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        MongoClient fault = commitFault(client, (session, method, args) -> {
            Object value = invoke(session, method, args);
            if (method.getName().equals("commitTransaction")) { commits.incrementAndGet(); throw unknownCommit(); }
            return value;
        });
        var faultyStore = new MongoSourceReceiptStore(fault, databaseName, clock, LIMITS_POLICY, verifier);
        var evidence = verified(PAYLOAD, Map.of(), 0);
        assertThat(faultyStore.admit(evidence).status()).isEqualTo(UNRESOLVED);
        assertThat(commits).hasValue(MongoSourceReceiptStore.ATTEMPTS);
        assertCounts(1, 1);
        String id = SourceReceiptCommitment.identity(evidence.scope());
        assertThat(store.readOnly().lookup(evidence, id).status()).isEqualTo(ADMITTED);
        assertThat(store.admit(evidence).status()).isEqualTo(EXISTING);
        assertCounts(1, 1);
    }

    @Test void interruptedCommitCannotLeaveAPartialReceiptOrReserveCapacity() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        MongoClient fault = commitFault(client, (session, method, args) -> {
            if (method.getName().equals("commitTransaction")) { commits.incrementAndGet(); throw unknownCommit(); }
            return invoke(session, method, args);
        });
        var faultyStore = new MongoSourceReceiptStore(fault, databaseName, clock, LIMITS_POLICY, verifier);
        var evidence = verified(PAYLOAD, Map.of(), 0);
        assertThat(faultyStore.admit(evidence).status()).isEqualTo(UNRESOLVED);
        assertThat(commits).hasValue(MongoSourceReceiptStore.ATTEMPTS);
        assertCounts(0, 0);
        assertThat(store.readOnly().lookup(evidence, SourceReceiptCommitment.identity(evidence.scope())).status()).isEqualTo(UNVERIFIED);
        assertThat(store.admit(evidence).status()).isEqualTo(ADMITTED);
        assertCounts(1, 1);
    }

    @Test void lostConflictCommitAcknowledgmentCannotRestoreTheEarlierAdmission() throws Exception {
        var first = verified(PAYLOAD, Map.of(), 0);
        String id = store.admit(first).receipt().id();
        Document original = database.getCollection(RECEIPTS).find().first();
        MongoClient fault = commitFault(client, (session, method, args) -> {
            Object value = invoke(session, method, args);
            if (method.getName().equals("commitTransaction")) { throw unknownCommit(); }
            return value;
        });
        var faultyStore = new MongoSourceReceiptStore(fault, databaseName, clock, LIMITS_POLICY, verifier);
        assertThat(faultyStore.admit(verified(PAYLOAD, Map.of("signingKeyVersion", "key-2"), 1)).status()).isEqualTo(UNRESOLVED);
        assertThat(store.readOnly().lookup(first, id).status()).isEqualTo(CONFLICT);
        assertThat(store.admit(first).status()).isEqualTo(CONFLICT);
        assertThat(database.getCollection(RECEIPTS).find().first()).isEqualTo(original);
        assertCounts(1, 1);
    }

    @Test void retryableAbortedTransactionReexecutesOnlyTheUniqueReceiptEffect() throws Exception {
        AtomicInteger commits = new AtomicInteger();
        MongoClient fault = commitFault(client, (session, method, args) -> {
            if (method.getName().equals("commitTransaction") && commits.getAndIncrement() == 0) {
                MongoException retryable = new MongoException(112, "TEST_ONLY_ABORTED_COMMIT");
                retryable.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                throw retryable;
            }
            return invoke(session, method, args);
        });
        var faultyStore = new MongoSourceReceiptStore(fault, databaseName, clock, LIMITS_POLICY, verifier);
        assertThat(faultyStore.admit(verified(PAYLOAD, Map.of(), 0)).status()).isEqualTo(ADMITTED);
        assertThat(commits).hasValue(2);
        assertCounts(1, 1);
    }

    @Test void receiptSurvivesClientAndStoreRestart() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        String id = store.admit(evidence).receipt().id();
        client.close();
        client = newClient(); database = client.getDatabase(databaseName);
        store = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier);
        assertThat(store.readOnly().lookup(evidence, id).status()).isEqualTo(ADMITTED);
        assertThat(store.admit(evidence).status()).isEqualTo(EXISTING);
        assertCounts(1, 1);
    }

    @Test void currentKeyRevocationAndSameIdKeyReplacementDenyOldCapabilities() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        String id = store.admit(evidence).receipt().id();
        var revoked = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier(policy(0, true)));
        assertThat(revoked.readOnly().lookup(evidence, id).status()).isEqualTo(REVOKED);
        assertThat(revoked.admit(evidence).status()).isEqualTo(REVOKED);
        var replacement = verifier(copyPolicy(ISSUER, "institution-a", "epoch-a", publicKey(1)));
        assertThat(replacement.currentDenial(evidence)).isEqualTo(SourceSignatureVerifier.Denied.TRUST_POLICY_CHANGED);
        var replacedStore = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, replacement);
        assertThat(replacedStore.readOnly().lookup(evidence, id).status()).isEqualTo(REVOKED);
        assertCounts(1, 1);
    }

    @Test void freshVerificationCannotReauthorizeAReceiptOutsideTheCurrentKeyEpoch() throws Exception {
        Map<String, Object> claims = Map.of("eventTime", NOW.plusSeconds(60).toString());
        var earlier = verified(PAYLOAD, claims, 0);
        String id = store.admit(earlier).receipt().id();
        Document originalReceipt = database.getCollection(RECEIPTS).find(eq("_id", id)).first();
        clock.instant = NOW.plusSeconds(120);
        var original = policy(0, false);
        var narrowed = new SourceVerificationProperties.TrustedKey(original.issuer(), original.institution(), original.source(),
                original.sourceEpoch(), original.signingKeyVersion(), original.algorithm(), original.publicKeyBase64(),
                NOW.plusSeconds(30), original.notAfter(), false, PURPOSE, AUDIENCE, original.policyVersion());
        verifier = verifier(narrowed);
        var fresh = verified(PAYLOAD, claims, 0);
        var restarted = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier);
        assertThat(restarted.readOnly().lookup(fresh, id).status()).isEqualTo(REVOKED);
        assertThat(restarted.admit(fresh).status()).isEqualTo(REVOKED);
        assertThat(database.getCollection(RECEIPTS).find(eq("_id", id)).first()).isEqualTo(originalReceipt);
        assertCounts(1, 1);
    }

    @Test void expiredReceiptCannotBeReadAndItsTombstonePreventsReAdmission() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        var receipt = store.admit(evidence).receipt();
        clock.instant = NOW.plus(Duration.ofMinutes(10));
        assertThat(store.readOnly().lookup(evidence, receipt.id()).status()).isEqualTo(TOMBSTONED);
        assertThat(database.getCollection(RECEIPTS).countDocuments()).isEqualTo(1); // Read seam never mutates storage.
        assertThat(store.compactExpired(10)).isEqualTo(1);
        assertThat(store.compactExpired(10)).isZero();
        assertCounts(1, 0);
        assertThat(store.admit(evidence).status()).isEqualTo(TOMBSTONED);
        assertThat(database.getCollection(IDENTITIES).find().first().getString("state")).isEqualTo("TOMBSTONED");
        var restarted = new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier);
        assertThat(restarted.admit(evidence).status()).isEqualTo(TOMBSTONED);
    }

    @Test void unknownCompactionCommitIsReportedAndNeverRemovesTheReplayFence() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        assertThat(store.admit(evidence).status()).isEqualTo(ADMITTED);
        clock.instant = NOW.plusSeconds(600);
        MongoClient fault = commitFault(client, (session, method, args) -> {
            Object value = invoke(session, method, args);
            if (method.getName().equals("commitTransaction")) { throw unknownCommit(); }
            return value;
        });
        var faultyStore = new MongoSourceReceiptStore(fault, databaseName, clock, LIMITS_POLICY, verifier);
        assertThatThrownBy(() -> faultyStore.compactExpired(1)).hasMessage("SOURCE_RECEIPT_COMPACTION_UNRESOLVED");
        assertCounts(1, 0);
        assertThat(store.admit(evidence).status()).isEqualTo(TOMBSTONED);
        assertThat(store.compactExpired(1)).isZero();
    }

    @Test void unavailableClientReturnsBoundedUncertaintyWithoutExposingDriverFailures() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        client.close();
        assertThat(store.admit(evidence).status()).isEqualTo(UNRESOLVED);
        assertThat(store.readOnly().lookup(evidence, SourceReceiptCommitment.identity(evidence.scope())).status()).isEqualTo(UNRESOLVED);
        assertThatThrownBy(() -> store.compactExpired(1)).hasMessage("SOURCE_RECEIPT_COMPACTION_UNRESOLVED");
    }

    @Test void missingReceiptOrIdentityCannotBeRepairedFromCallerClaims() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        String id = store.admit(evidence).receipt().id();
        database.getCollection(RECEIPTS).deleteOne(eq("_id", id));
        assertThat(store.readOnly().lookup(evidence, id).status()).isEqualTo(UNRESOLVED);
        assertThat(store.admit(evidence).status()).isEqualTo(UNRESOLVED);
        assertCounts(1, 0);
        assertThat(store.readOnly().lookup(null, id).status()).isEqualTo(UNVERIFIED);
        assertThat(store.readOnly().lookup(evidence, "0".repeat(64)).status()).isEqualTo(UNVERIFIED);
    }

    @Test void orphanedOrTamperedReceiptNeverSubstitutesForAnExactIdentityFence() throws Exception {
        var evidence = verified(PAYLOAD, Map.of(), 0);
        String id = store.admit(evidence).receipt().id();
        database.getCollection(RECEIPTS).updateOne(eq("_id", id),
                new Document("$set", new Document("payloadCommitment", "0".repeat(64))));
        assertThat(store.readOnly().lookup(evidence, id).status()).isEqualTo(UNRESOLVED);
        assertThat(store.admit(evidence).status()).isEqualTo(UNRESOLVED);
        database.getCollection(IDENTITIES).deleteOne(eq("_id", id));
        assertThat(store.readOnly().lookup(evidence, id).status()).isEqualTo(UNVERIFIED);
        assertThat(store.admit(evidence).status()).isEqualTo(UNRESOLVED);
        assertThat(database.getCollection(IDENTITIES).countDocuments()).isZero();
        assertThat(database.getCollection(RECEIPTS).countDocuments()).isEqualTo(1);
        assertThat(database.getCollection(LIMITS).find().first().getInteger("count")).isEqualTo(1);
    }

    @Test void replayHorizonAndLifetimeCapacityRemainBoundedIncludingTombstones() throws Exception {
        var old = verified(PAYLOAD, Map.of("eventTime", NOW.minusSeconds(300).toString()), 0);
        assertThat(store.admit(old).status()).isEqualTo(REPLAY_EXPIRED);
        assertCounts(0, 0);
        var capped = new MongoSourceReceiptStore(client, databaseName + "_cap", clock,
                new SourceReceiptStore.Limits(Duration.ofMinutes(5), Duration.ofMinutes(10), 1), verifier);
        var first = verified(PAYLOAD, Map.of(), 0);
        assertThat(capped.admit(first).status()).isEqualTo(ADMITTED);
        assertThat(capped.admit(verified(PAYLOAD, Map.of("sourceEventId", "event-2"), 0)).status()).isEqualTo(CAPACITY_EXCEEDED);
        assertThat(capped.admit(first).status()).isEqualTo(EXISTING);
        clock.instant = NOW.plusSeconds(600);
        assertThat(capped.compactExpired(1)).isEqualTo(1);
        assertThat(capped.admit(verified(PAYLOAD, Map.of("sourceEventId", "event-2", "eventTime", clock.instant.toString()), 0)).status())
                .isEqualTo(CAPACITY_EXCEEDED);
    }

    @Test void startupRejectsDestructiveTtlAndMissingCapacityStateWithoutRepair() {
        database.getCollection(IDENTITIES).createIndex(new Document("expiresAt", 1),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
        assertThatThrownBy(() -> new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier))
                .hasMessage("SOURCE_RECEIPT_SCHEMA_INCOMPATIBLE");
        database.getCollection(IDENTITIES).dropIndex("expiresAt_1");
        database.getCollection(LIMITS).deleteOne(eq("_id", "capacity"));
        assertThatThrownBy(() -> new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier))
                .hasMessage("SOURCE_RECEIPT_CAPACITY_POLICY_INCOMPATIBLE");
        assertThat(database.getCollection(LIMITS).countDocuments()).isZero();
    }

    @Test void startupRejectsRelaxedCollectionValidationWithoutChangingIt() {
        database.runCommand(new Document("collMod", RECEIPTS).append("validationAction", "warn"));
        assertThatThrownBy(() -> new MongoSourceReceiptStore(client, databaseName, clock, LIMITS_POLICY, verifier))
                .hasMessage("SOURCE_RECEIPT_SCHEMA_INCOMPATIBLE");
        assertThat(database.listCollections().filter(eq("name", RECEIPTS)).first()
                .get("options", Document.class).getString("validationAction")).isEqualTo("warn");
    }

    @Test void enabledProductionFactoryUsesExplicitReplicaSetConfiguration() throws Exception {
        var policy = policy(0, false);
        var currentVerifier = new SourceSignatureVerifier(new SourceVerificationProperties(List.of(
                new SourceVerificationProperties.TrustedKey(policy.issuer(), policy.institution(), policy.source(),
                        policy.sourceEpoch(), policy.signingKeyVersion(), policy.algorithm(), policy.publicKeyBase64(),
                        Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2100-01-01T00:00:00Z"), false,
                        PURPOSE, AUDIENCE, "policy-1"))), Clock.systemUTC());
        var evidence = (SourceSignatureVerifier.Verified) currentVerifier.verify(
                proof(PAYLOAD, Map.of("eventTime", Instant.now().minusSeconds(10).toString()), 0), PAYLOAD,
                new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE));
        new ApplicationContextRunner().withUserConfiguration(SourceReceiptConfiguration.class)
                .withBean(SourceSignatureVerifier.class, () -> currentVerifier)
                .withPropertyValues("fraud.source-receipts.enabled=true", "fraud.source-receipts.mongo-uri=" + MONGO.getReplicaSetUrl(),
                        "fraud.source-receipts.database=" + databaseName + "_factory", "fraud.source-receipts.replay-horizon=PT5M",
                        "fraud.source-receipts.receipt-retention=PT10M", "fraud.source-receipts.max-receipts=10")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SourceReceiptStore.class).admit(null).status()).isEqualTo(UNVERIFIED);
                    assertThat(context.getBean(SourceReceiptStore.class).admit(evidence).status()).isEqualTo(ADMITTED);
                });
    }

    private SourceSignatureVerifier verifier(SourceVerificationProperties.TrustedKey... policies) {
        return new SourceSignatureVerifier(new SourceVerificationProperties(List.of(policies)), clock);
    }
    private SourceSignatureVerifier.Verified verified(byte[] payload, Map<String, Object> claims, int key) throws Exception {
        var result = verifier.verify(proof(payload, claims, key), payload, new SourceSignatureVerifier.Context(PURPOSE, AUDIENCE));
        assertThat(result).isInstanceOf(SourceSignatureVerifier.Verified.class);
        return (SourceSignatureVerifier.Verified) result;
    }
    private static SourceVerificationProperties.TrustedKey copyPolicy(String issuer, String institution, String epoch, String key) {
        var original = policy(0, false);
        return new SourceVerificationProperties.TrustedKey(issuer, institution, original.source(), epoch, original.signingKeyVersion(),
                original.algorithm(), key, original.notBefore(), original.notAfter(), false, PURPOSE, AUDIENCE, "policy-1");
    }
    private void assertCounts(long identities, long receipts) {
        assertThat(database.getCollection(IDENTITIES).countDocuments()).isEqualTo(identities);
        assertThat(database.getCollection(RECEIPTS).countDocuments()).isEqualTo(receipts);
        assertThat(database.getCollection(LIMITS).find().first().getInteger("count")).isEqualTo((int) identities);
    }
    private static MongoClient newClient() {
        return MongoClients.create(MongoClientSettings.builder().applyConnectionString(new ConnectionString(MONGO.getReplicaSetUrl()))
                .timeout(5, TimeUnit.SECONDS).build());
    }
    private static MongoException unknownCommit() {
        MongoException failure = new MongoException(91, "TEST_ONLY_LOST_COMMIT_ACK");
        failure.addLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL); return failure;
    }
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
    @FunctionalInterface private interface SessionCall { Object call(ClientSession session, Method method, Object[] args) throws Throwable; }
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
