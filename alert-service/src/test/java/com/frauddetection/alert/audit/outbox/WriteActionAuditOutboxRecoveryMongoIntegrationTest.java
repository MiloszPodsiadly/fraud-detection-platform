package com.frauddetection.alert.audit.outbox;

import com.frauddetection.alert.audit.AuditChainLockDocument;
import com.frauddetection.alert.audit.AuditChainLockRepository;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.audit.GovernanceEventVerificationService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
class WriteActionAuditOutboxRecoveryMongoIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final String EVENT_ID = "policy-event-1";
    private static final String COMMITMENT = "a".repeat(64);
    private static final GovernanceEventCommitment.Kind KIND = GovernanceEventCommitment.Kind.POLICY_GOVERNANCE;
    private static final PolicyGovernanceActor OPERATOR = new PolicyGovernanceActor("operator-a", Set.of(
            ModelPolicyAuthority.AUDIT_READ, ModelPolicyAuthority.AUDIT_RECOVER, ModelPolicyAuthority.PRODUCTION));

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private GovernanceEventVerificationService verifier;
    private WriteActionAuditOutboxRecoveryService recovery;
    private String databaseName;

    @BeforeEach
    void setUp() throws Exception {
        databaseName = "audit_outbox_recovery_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create(MONGO.getReplicaSetUrl(databaseName));
        factory = new SimpleMongoClientDatabaseFactory(client, databaseName);
        mongo = new MongoTemplate(factory);
        verifier = mock(GovernanceEventVerificationService.class);
        recovery = new WriteActionAuditOutboxRecoveryService(mongo,
                new TransactionTemplate(new MongoTransactionManager(factory)), verifier,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        mongo.insert(failedObligation(5, 0));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
        if (client != null) client.close();
    }

    @Test
    void retryPreservesAttemptHistoryAndReplaysTheSameImmutableReceipt() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(noAppend());
        var command = new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                "recover-1", "Audit store restored after outage", "RETRY");

        var first = recovery.recover(OPERATOR, KIND.name(), EVENT_ID, command);
        var restarted = new WriteActionAuditOutboxRecoveryService(mongo,
                new TransactionTemplate(new MongoTransactionManager(factory)), verifier,
                Clock.fixed(NOW.plusSeconds(15), ZoneOffset.UTC), new ObjectMapper());
        var replay = restarted.recover(OPERATOR, KIND.name(), EVENT_ID, command);
        var obligation = mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID), WriteActionAuditOutboxRecord.class);
        var storedReceipt = mongo.findById("recover-1", WriteActionAuditRecoveryEvent.class);

        assertThat(first).isEqualTo(replay);
        assertThat(first.receiptSha256()).matches("[0-9a-f]{64}");
        assertThat(first.localReceiptStatus()).isEqualTo("COMMITTED_IN_LOCAL_MONGO");
        assertThat(first.receiptIntegrityStatus()).isEqualTo("VALID");
        assertThat(first.referencedEvidenceStatus()).isEqualTo("NO_APPEND_PROVEN");
        assertThat(first.independentWitnessStatus()).isEqualTo("NOT_INDEPENDENTLY_ANCHORED");
        assertThat(obligation.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PENDING);
        assertThat(obligation.getAttemptCount()).isEqualTo(5);
        assertThat(obligation.getMaxAttempts()).isEqualTo(5);
        assertThat(obligation.getRecoveryCount()).isEqualTo(1);
        assertThat(storedReceipt.commitmentSha256()).isEqualTo(COMMITMENT);
        assertThat(storedReceipt.auditRequestId()).isEqualTo("gov:" + COMMITMENT);
        assertThat(storedReceipt.governanceEventId()).isEqualTo(EVENT_ID);
        var claimStore = new WriteActionAuditOutboxClaimStore(mongo);
        var claim = claimStore.claimForPublishing(obligation.getOutboxId(), NOW, "publisher-A",
                java.time.Duration.ofMinutes(5)).orElseThrow();
        assertThat(claimStore.markFailed(claim, NOW.plusSeconds(1), "AUDIT_SERVICE_UNAVAILABLE")).isTrue();
        var afterRecoveredAttempt = mongo.findById(obligation.getOutboxId(), WriteActionAuditOutboxRecord.class);
        assertThat(afterRecoveredAttempt.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        assertThat(afterRecoveredAttempt.getAttemptCount()).isEqualTo(6);
        assertThat(afterRecoveredAttempt.getRecoveryCount()).isEqualTo(1);
        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "recover-1", "different intent", "RETRY")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void unknownAbsenceIsRecheckedUnderFencedTakeoverBeforeRetryIsQueued() {
        var unknown = new GovernanceEventVerificationService.Verification(KIND.name(), EVENT_ID,
                "OUTCOME_UNKNOWN", COMMITMENT, null, null, null, "NOT_VERIFIED", "ORPHANED_LEASE");
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(unknown);
        when(verifier.verifyUnderRecoveryBarrier(org.mockito.ArgumentMatchers.eq(OPERATOR),
                org.mockito.ArgumentMatchers.eq(KIND), org.mockito.ArgumentMatchers.eq(EVENT_ID),
                org.mockito.ArgumentMatchers.any(AuditChainLockDocument.class))).thenReturn(noAppend());
        mongo.save(new AuditChainLockDocument("source_service:alert-service", "crashed-writer",
                NOW.minusSeconds(31), 7L));
        AuditChainLockRepository locks = new AuditChainLockRepository(mongo);
        var fencedRecovery = new WriteActionAuditOutboxRecoveryService(mongo,
                new TransactionTemplate(new MongoTransactionManager(factory)), verifier,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), locks);

        var result = fencedRecovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "orphan-retry", "Recover abandoned audit append", "RETRY"));
        AuditChainLockDocument persisted = mongo.findById("source_service:alert-service",
                AuditChainLockDocument.class);

        assertThat(result.classification()).isEqualTo("RETRY_QUEUED");
        assertThat(persisted).isNotNull();
        assertThat(persisted.generation()).isEqualTo(8L);
        assertThat(persisted.ownerToken()).isNull();
        assertThat(persisted.lockedUntil()).isEqualTo(Instant.EPOCH);
        verify(verifier).verifyUnderRecoveryBarrier(org.mockito.ArgumentMatchers.eq(OPERATOR),
                org.mockito.ArgumentMatchers.eq(KIND), org.mockito.ArgumentMatchers.eq(EVENT_ID),
                org.mockito.ArgumentMatchers.argThat(lease -> lease != null && lease.generation() == 8L));
    }

    @Test
    void exactCommittedAppendOnlyReconcilesAcknowledgement() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(exactAppend());
        var result = recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "reconcile-1", "Publisher acknowledgement was lost", "RECONCILE"));
        var obligation = mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID), WriteActionAuditOutboxRecord.class);

        assertThat(result.classification()).isEqualTo("ACKNOWLEDGEMENT_RECONCILED");
        assertThat(obligation.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PUBLISHED);
        assertThat(obligation.getAttemptCount()).isEqualTo(5);
        assertThat(obligation.getRecoveryCount()).isZero();
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                com.frauddetection.alert.audit.AuditEventDocument.class)).isZero();
    }

    @Test
    void exactCommittedAppendCannotBeQueuedAgainAsRetry() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(exactAppend());

        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "exact-cannot-retry", "Append already committed", "RETRY")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Requested action does not match");
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID), WriteActionAuditOutboxRecord.class)
                .getStatus()).isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        assertThat(mongo.findById("exact-cannot-retry", WriteActionAuditRecoveryEvent.class)).isNull();
    }

    @Test
    void recoveryCannotTakeOverAnActivePublisherClaim() {
        var active = failedObligation(5, 0);
        active.setStatus(WriteActionAuditOutboxStatus.PUBLISHING);
        active.setClaimOwner("publisher");
        active.setClaimToken("live-token");
        active.setClaimGeneration(4);
        active.setClaimedAt(NOW);
        active.setClaimExpiresAt(NOW.plusSeconds(300));
        mongo.save(active);
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(noAppend());

        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand("race-1", "retry", "RETRY")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mongo.findById(active.getOutboxId(), WriteActionAuditOutboxRecord.class).getClaimToken())
                .isEqualTo("live-token");
        assertThat(mongo.findById("race-1", WriteActionAuditRecoveryEvent.class)).isNull();
    }

    @Test
    void concurrentOperatorsCannotBothConsumeOneRecoveryCycle() throws Exception {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(noAppend());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> recoverAfter(start, "operator-race-a"));
            var second = executor.submit(() -> recoverAfter(start, "operator-race-b"));
            start.countDown();
            Object firstResult = first.get(15, TimeUnit.SECONDS);
            Object secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(WriteActionAuditOutboxRecoveryService.RecoveryView.class::isInstance)).hasSize(1);
            assertThat(java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(RuntimeException.class::isInstance)).hasSize(1);
        }
        var obligation = mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID), WriteActionAuditOutboxRecord.class);
        assertThat(obligation.getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PENDING);
        assertThat(obligation.getRecoveryCount()).isEqualTo(1);
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(), WriteActionAuditRecoveryEvent.class))
                .isEqualTo(1L);
    }

    @Test
    void unavailableMongoEvidenceDoesNotBecomeProofOfNoAppend() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID))
                .thenThrow(new DataAccessResourceFailureException("mongo unavailable"));

        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "mongo-unavailable", "Retry after Mongo outage", "RETRY")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Audit evidence is unavailable");
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID), WriteActionAuditOutboxRecord.class)
                .getStatus()).isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        assertThat(mongo.findById("mongo-unavailable", WriteActionAuditRecoveryEvent.class)).isNull();
    }

    @Test
    void recoveryCasRejectsCommitmentFieldChangedAfterEvidenceVerification() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenAnswer(invocation -> {
            var changed = mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID),
                    WriteActionAuditOutboxRecord.class);
            changed.setActor("changed-after-verification");
            mongo.save(changed);
            return noAppend();
        });

        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "toctou-1", "Retry after evidence check", "RETRY")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID),
                WriteActionAuditOutboxRecord.class).getStatus())
                .isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        assertThat(mongo.findById("toctou-1", WriteActionAuditRecoveryEvent.class)).isNull();
    }

    @Test
    void recoveryRechecksSourceAuditEventAndAnchorAfterInitialEvidenceRead() throws Exception {
        CountDownLatch initialEvidenceRead = new CountDownLatch(1);
        CountDownLatch continueRecovery = new CountDownLatch(1);
        AtomicInteger verificationCalls = new AtomicInteger();
        mongo.getCollection("model_policy_governance_history").insertOne(new Document("_id", EVENT_ID)
                .append("rationale", "before"));
        mongo.getCollection("audit_events").insertOne(new Document("_id", "referenced-audit-event")
                .append("event_hash", "a".repeat(64)));
        mongo.getCollection("audit_chain_anchors").insertOne(new Document("_id", "referenced-anchor")
                .append("last_event_hash", "b".repeat(64)));
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenAnswer(invocation -> {
            if (verificationCalls.incrementAndGet() == 1) {
                var initialEvidence = noAppend();
                initialEvidenceRead.countDown();
                if (!continueRecovery.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to resume recovery");
                }
                return initialEvidence;
            }
            assertThat(mongo.getCollection("model_policy_governance_history")
                    .find(new Document("_id", EVENT_ID)).first().getString("rationale"))
                    .isEqualTo("changed-after-first-read");
            assertThat(mongo.getCollection("audit_events")
                    .find(new Document("_id", "referenced-audit-event")).first().getString("event_hash"))
                    .isEqualTo("f".repeat(64));
            assertThat(mongo.getCollection("audit_chain_anchors")
                    .find(new Document("_id", "referenced-anchor")).first().getString("last_event_hash"))
                    .isEqualTo("e".repeat(64));
            return tampered();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                    new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                            "changed-evidence", "Retry after evidence change", "RETRY")));
            assertThat(initialEvidenceRead.await(10, TimeUnit.SECONDS)).isTrue();
            mongo.getCollection("model_policy_governance_history").updateOne(new Document("_id", EVENT_ID),
                    new Document("$set", new Document("rationale", "changed-after-first-read")));
            mongo.getCollection("audit_events").updateOne(new Document("_id", "referenced-audit-event"),
                    new Document("$set", new Document("event_hash", "f".repeat(64))));
            mongo.getCollection("audit_chain_anchors").updateOne(new Document("_id", "referenced-anchor"),
                    new Document("$set", new Document("last_event_hash", "e".repeat(64))));
            continueRecovery.countDown();
            assertThatThrownBy(() -> pending.get(15, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class);
        } finally {
            continueRecovery.countDown();
        }
        verify(verifier, times(2)).verify(OPERATOR, KIND, EVENT_ID);
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID),
                WriteActionAuditOutboxRecord.class).getStatus())
                .isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        assertThat(mongo.findById("changed-evidence", WriteActionAuditRecoveryEvent.class)).isNull();
        assertThat(mongo.getCollection("model_policy_governance_history")
                .find(new Document("_id", EVENT_ID)).first().getString("rationale"))
                .isEqualTo("changed-after-first-read");
        assertThat(mongo.getCollection("audit_events")
                .find(new Document("_id", "referenced-audit-event")).first().getString("event_hash"))
                .isEqualTo("f".repeat(64));
        assertThat(mongo.getCollection("audit_chain_anchors")
                .find(new Document("_id", "referenced-anchor")).first().getString("last_event_hash"))
                .isEqualTo("e".repeat(64));
    }

    @Test
    void transactionalRecheckRejectsAppendThatAppearedAfterNoAppendPreflight() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(noAppend(), exactAppend());

        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "append-race", "Append raced with retry", "RETRY")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("evidence changed");

        verify(verifier, times(2)).verify(OPERATOR, KIND, EVENT_ID);
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID),
                WriteActionAuditOutboxRecord.class).getStatus())
                .isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        assertThat(mongo.findById("append-race", WriteActionAuditRecoveryEvent.class)).isNull();
    }

    @Test
    void exactAppendRacingReconcileProducesOneReceiptAndNoSecondAuditAppend() throws Exception {
        CountDownLatch initialEvidenceRead = new CountDownLatch(1);
        CountDownLatch continueRecovery = new CountDownLatch(1);
        AtomicInteger verificationCalls = new AtomicInteger();
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenAnswer(invocation -> {
            if (verificationCalls.incrementAndGet() == 1) {
                var initialEvidence = exactAppend();
                initialEvidenceRead.countDown();
                if (!continueRecovery.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to resume reconciliation");
                }
                return initialEvidence;
            }
            assertThat(mongo.getCollection("audit_events")
                    .find(new Document("request_id", "gov:" + COMMITMENT)).first()).isNotNull();
            return exactAppend();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                    new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                            "reconcile-append-race", "Reconcile publisher append", "RECONCILE")));
            assertThat(initialEvidenceRead.await(10, TimeUnit.SECONDS)).isTrue();
            mongo.getCollection("audit_events").insertOne(new Document("_id", "committed-audit-event")
                    .append("request_id", "gov:" + COMMITMENT).append("event_hash", "d".repeat(64)));
            continueRecovery.countDown();
            assertThat(pending.get(15, TimeUnit.SECONDS).classification())
                    .isEqualTo("ACKNOWLEDGEMENT_RECONCILED");
        } finally {
            continueRecovery.countDown();
        }
        verify(verifier, times(2)).verify(OPERATOR, KIND, EVENT_ID);
        assertThat(mongo.getCollection("audit_events")
                .countDocuments(new Document("request_id", "gov:" + COMMITMENT))).isEqualTo(1L);
        assertThat(mongo.findById("reconcile-append-race", WriteActionAuditRecoveryEvent.class)).isNotNull();
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID),
                WriteActionAuditOutboxRecord.class).getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PUBLISHED);
    }

    @Test
    void uncertainCommitIsResolvedFromTheCommittedImmutableReceipt() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(noAppend());
        MongoTransactionManager delegate = new MongoTransactionManager(factory);
        PlatformTransactionManager commitThenLoseAcknowledgement = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return delegate.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) {
                delegate.commit(status);
                throw new TransactionSystemException("Simulated lost commit acknowledgement");
            }

            @Override
            public void rollback(TransactionStatus status) {
                delegate.rollback(status);
            }
        };
        var uncertainRecovery = new WriteActionAuditOutboxRecoveryService(mongo,
                new TransactionTemplate(commitThenLoseAcknowledgement), verifier,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());

        var result = uncertainRecovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "lost-commit-ack", "Retry after uncertain commit", "RETRY"));

        assertThat(result.classification()).isEqualTo("RETRY_QUEUED");
        assertThat(result.receiptSha256()).matches("[0-9a-f]{64}");
        assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(),
                WriteActionAuditRecoveryEvent.class)).isEqualTo(1L);
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID),
                WriteActionAuditOutboxRecord.class).getStatus()).isEqualTo(WriteActionAuditOutboxStatus.PENDING);
    }

    @Test
    void historyReportsMalformedReceiptFieldsAsIntegrityFailure() {
        mongo.insert(new WriteActionAuditRecoveryEvent("malformed-1", "outbox-1", KIND.name(), EVENT_ID,
                COMMITMENT, "gov:" + COMMITMENT, "operator-a", null, "RETRY", "RETRY_QUEUED",
                5, 1, NOW, "NOT_VERIFIED", "not-a-valid-receipt-hash"));

        var history = recovery.history(OPERATOR);

        assertThat(history).singleElement()
                .extracting(WriteActionAuditOutboxRecoveryService.RecoveryView::classification)
                .isEqualTo("RECOVERY_RECEIPT_INTEGRITY_FAILURE");
        assertThat(history.getFirst().receiptSha256()).isNull();
        assertThat(history.getFirst().localReceiptStatus()).isEqualTo("COMMITTED_IN_LOCAL_MONGO");
        assertThat(history.getFirst().receiptIntegrityStatus()).isEqualTo("INVALID");
        assertThat(history.getFirst().referencedEvidenceStatus()).isEqualTo("NOT_VERIFIED");
        assertThat(history.getFirst().independentWitnessStatus()).isEqualTo("NOT_INDEPENDENTLY_ANCHORED");
        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                        "malformed-1", "retry", "RETRY")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("receipt integrity failure");
    }

    @Test
    void tamperedEvidenceAndExhaustedBudgetCannotClearPermanentFailure() {
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(tampered());
        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand("tampered-1", "retry", "RETRY")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mongo.findById(GovernanceEventCommitment.idFor(KIND, EVENT_ID), WriteActionAuditOutboxRecord.class)
                .getStatus()).isEqualTo(WriteActionAuditOutboxStatus.FAILED_PERMANENT);

        var exhausted = failedObligation(8, WriteActionAuditOutboxRecoveryService.MAX_RECOVERIES);
        mongo.save(exhausted);
        when(verifier.verify(OPERATOR, KIND, EVENT_ID)).thenReturn(noAppend());
        assertThatThrownBy(() -> recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                new WriteActionAuditOutboxRecoveryService.RecoveryCommand("budget-1", "retry", "RETRY")))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(mongo.findById(exhausted.getOutboxId(), WriteActionAuditOutboxRecord.class).getRecoveryCount())
                .isEqualTo(WriteActionAuditOutboxRecoveryService.MAX_RECOVERIES);
    }

    @Test
    void ordinaryAuditReaderCannotRecover() {
        var reader = new PolicyGovernanceActor("reader", Set.of(ModelPolicyAuthority.AUDIT_READ,
                ModelPolicyAuthority.PRODUCTION));
        assertThatThrownBy(() -> recovery.list(reader)).isInstanceOf(AccessDeniedException.class);
    }

    private WriteActionAuditOutboxRecord failedObligation(int attempts, int recoveries) {
        var record = new WriteActionAuditOutboxRecord();
        String id = GovernanceEventCommitment.idFor(KIND, EVENT_ID);
        record.setOutboxId(id);
        record.setIdempotencyKey(id);
        record.setContractVersion(GovernanceEventCommitment.VERSION);
        record.setAction(com.frauddetection.alert.audit.AuditAction.COMMIT_GOVERNANCE_EVENT);
        record.setResourceType(com.frauddetection.alert.audit.AuditResourceType.MODEL_GOVERNANCE_EVENT);
        record.setResourceId(EVENT_ID);
        record.setActor("business-actor");
        record.setGovernanceEventKind(KIND.name());
        record.setGovernanceEventId(EVENT_ID);
        record.setGovernanceEvidenceHash(COMMITMENT);
        record.setStatus(WriteActionAuditOutboxStatus.FAILED_PERMANENT);
        record.setAttemptCount(attempts);
        record.setMaxAttempts(5);
        record.setRecoveryCount(recoveries);
        record.setCreatedAt(NOW.minusSeconds(30));
        record.setLastErrorCode("AUDIT_SERVICE_UNAVAILABLE");
        return record;
    }

    private GovernanceEventVerificationService.Verification noAppend() {
        return new GovernanceEventVerificationService.Verification(KIND.name(), EVENT_ID, "BLOCKED",
                COMMITMENT, null, null, null, "NOT_VERIFIED", null, "NOT_REVERIFIED");
    }

    private GovernanceEventVerificationService.Verification exactAppend() {
        return new GovernanceEventVerificationService.Verification(KIND.name(), EVENT_ID, "BLOCKED",
                COMMITMENT, "b".repeat(64), 9L, "anchor-9", "NOT_VERIFIED", null, "DIGEST_BOUND_NOT_REVERIFIED");
    }

    private GovernanceEventVerificationService.Verification tampered() {
        return new GovernanceEventVerificationService.Verification(KIND.name(), EVENT_ID, "TAMPERED",
                null, null, null, null, "NOT_VERIFIED", "EVENT_HASH_MISMATCH", "NOT_REVERIFIED");
    }

    private Object recoverAfter(CountDownLatch start, String operationId) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("operator race start timed out");
            return recovery.recover(OPERATOR, KIND.name(), EVENT_ID,
                    new WriteActionAuditOutboxRecoveryService.RecoveryCommand(operationId, "concurrent retry", "RETRY"));
        } catch (RuntimeException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new IllegalStateException(exception);
        }
    }
}
