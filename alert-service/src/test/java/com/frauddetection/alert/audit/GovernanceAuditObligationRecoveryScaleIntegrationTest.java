package com.frauddetection.alert.audit;

import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecoveryService;
import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxStatus;
import com.frauddetection.alert.governance.audit.GovernanceEventCommitment;
import com.frauddetection.alert.governance.audit.GovernanceEventVerificationService;
import com.frauddetection.alert.governance.modelpolicy.PolicyGovernanceActor;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@Testcontainers
@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
@Tag("invariant-proof")
class GovernanceAuditObligationRecoveryScaleIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final PolicyGovernanceActor OPERATOR = new PolicyGovernanceActor("audit-operator", Set.of(
            ModelPolicyAuthority.AUDIT_READ, ModelPolicyAuthority.AUDIT_RECOVER, ModelPolicyAuthority.PRODUCTION));

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    private MongoClient client;
    private SimpleMongoClientDatabaseFactory factory;
    private MongoTemplate mongo;
    private AuditEventRepository events;
    private AuditAnchorRepository anchors;
    private AuditChainIndexInitializer indexes;
    private String databaseName;

    @BeforeEach
    void setUp() {
        databaseName = "audit_obligation_scale_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create(MONGO.getReplicaSetUrl(databaseName));
        factory = new SimpleMongoClientDatabaseFactory(client, databaseName);
        mongo = new MongoTemplate(factory);
        indexes = new AuditChainIndexInitializer(mongo);
        indexes.ensureIndexes();
        events = spy(new AuditEventRepository(mongo));
        anchors = new AuditAnchorRepository(mongo);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongo != null) mongo.getDb().drop();
        if (factory != null) factory.destroy();
        if (client != null) client.close();
    }

    @Test
    void exactRecoveryRemainsBoundedAcrossAllFormerScanThresholdsAndBeyondOneHundredThousand() {
        int[] boundaries = {0, 1, 9_999, 10_000, 10_001, 100_001};
        int insertedThrough = 0;
        for (int boundary : boundaries) {
            appendSyntheticHistoryThrough(boundary, insertedThrough);
            insertedThrough = boundary;
            String eventId = "scale-obligation-" + boundary;
            GovernanceEventCommitment.Kind kind = GovernanceEventCommitment.Kind.TRUST_CHANGE;
            PolicyTrustChangeAuditDocument source = sourceEvent(eventId);
            mongo.insert(source);
            GovernanceEventCommitment.record(mongo, kind, eventId, source, "CURRENT_INSTITUTION/TEST",
                    source.actorId(), source.requestId(), "operation-" + eventId,
                    source.assertionSha256(), source.requestId(), source.occurredAt());
            String outboxId = GovernanceEventCommitment.idFor(kind, eventId);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(outboxId)),
                    new Update().set("status", WriteActionAuditOutboxStatus.FAILED_PERMANENT)
                            .unset("next_attempt_at"), WriteActionAuditOutboxRecord.class);

            MongoTransactionManager transactionManager = new MongoTransactionManager(factory);
            GovernanceEventVerificationService verification = new GovernanceEventVerificationService(
                    mongo, events, anchors, null, null, null, null, transactionManager);
            var result = verification.verify(OPERATOR, kind, eventId);

            assertThat(result.status()).as("chain size %s", boundary).isEqualTo("BLOCKED");
            assertThat(result.canonicalSha256()).isNotBlank();
            verify(events, never()).findFullChain(anyString(), anyInt());
            verify(events, never()).countByPartitionKey(anyString());

            if (boundary == 100_001) {
                var recovery = new WriteActionAuditOutboxRecoveryService(mongo,
                        transactionManager, verification, new ObjectMapper());
                var queued = recovery.recover(OPERATOR, kind.name(), eventId,
                        new WriteActionAuditOutboxRecoveryService.RecoveryCommand(
                                "scale-recovery-100001", "Exact request lookup proved no append", "RETRY"));
                assertThat(queued.classification()).isEqualTo("RETRY_QUEUED");
                assertThat(queued.attemptCount()).isZero();
                assertThat(queued.recoveryCount()).isEqualTo(1);
            }
        }
    }

    @Test
    void realMongoExactAppendReconcilesWithoutFullChainVerification() {
        GovernanceEventCommitment.Kind kind = GovernanceEventCommitment.Kind.TRUST_CHANGE;
        String eventId = "exact-append-scale-proof";
        PolicyTrustChangeAuditDocument source = sourceEvent(eventId);
        mongo.insert(source);
        String commitment = GovernanceEventCommitment.record(mongo, kind, eventId, source,
                "CURRENT_INSTITUTION/TEST", source.actorId(), source.requestId(), "operation-exact",
                source.assertionSha256(), source.requestId(), source.occurredAt());
        String outboxId = GovernanceEventCommitment.idFor(kind, eventId);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(outboxId)),
                new Update().set("status", WriteActionAuditOutboxStatus.PUBLISHED), WriteActionAuditOutboxRecord.class);
        AuditEventDocument audit = AuditEventDocument.from("exact-audit-event", new AuditEvent(
                new AuditActor(source.actorId(), Set.of(), Set.of()), AuditAction.COMMIT_GOVERNANCE_EVENT,
                AuditResourceType.MODEL_GOVERNANCE_EVENT, eventId, source.occurredAt(), "operation-exact",
                "gov:" + commitment, AuditOutcome.SUCCESS, AuditFailureCategory.NONE, null), null, 1L);
        events.insert(audit);
        anchors.insert(AuditAnchorDocument.from("exact-local-anchor", audit));

        GovernanceEventVerificationService verification = new GovernanceEventVerificationService(
                mongo, events, anchors, null, null, null, null, new MongoTransactionManager(factory));
        var result = verification.verify(OPERATOR, kind, eventId);

        assertThat(result.status()).isEqualTo("LOCAL_VERIFIED_EXTERNAL_UNCONFIRMED");
        assertThat(result.auditEventSha256()).isEqualTo(audit.eventHash());
        assertThat(result.chainPosition()).isEqualTo(1L);
        assertThat(result.localAnchorId()).isEqualTo("exact-local-anchor");
        verify(events, never()).findFullChain(anyString(), anyInt());
        verify(events, never()).countByPartitionKey(anyString());
    }

    private void appendSyntheticHistoryThrough(int target, int current) {
        if (target <= current) return;
        if (target == 1) {
            AuditEventDocument head = historyEvent(1L, null);
            events.insert(head);
            anchors.insert(AuditAnchorDocument.from("scale-anchor-1", head));
            return;
        }
        int firstSynthetic = Math.max(1, current + 1);
        int predecessorPosition = target - 1;
        List<Document> batch = new ArrayList<>(10_000);
        for (int position = firstSynthetic; position < predecessorPosition; position++) {
            batch.add(new Document("_id", "scale-event-" + position)
                    .append("partition_key", "source_service:alert-service")
                    .append("chain_position", (long) position));
            if (batch.size() == 10_000) {
                mongo.getCollection("audit_events").insertMany(List.copyOf(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) mongo.getCollection("audit_events").insertMany(List.copyOf(batch));

        AuditEventDocument predecessor;
        if (predecessorPosition == current) {
            predecessor = events.findByPartitionKeyAndChainPositionBetween(
                    "source_service:alert-service", predecessorPosition, predecessorPosition, 1).getFirst();
        } else {
            predecessor = historyEvent(predecessorPosition, "preceding-synthetic-hash");
            events.insert(predecessor);
        }
        AuditEventDocument head = historyEvent(target, predecessor.eventHash());
        events.insert(head);
        anchors.insert(AuditAnchorDocument.from("scale-anchor-" + target, head));
    }

    private AuditEventDocument historyEvent(long position, String previousHash) {
        AuditEvent event = new AuditEvent(new AuditActor("history-actor", Set.of("ANALYST"), Set.of()),
                AuditAction.UPDATE_FRAUD_CASE, AuditResourceType.FRAUD_CASE,
                "history-resource-" + position, NOW, "history-correlation-" + position,
                "history-request-" + position, AuditOutcome.SUCCESS, AuditFailureCategory.NONE, null);
        return AuditEventDocument.from("scale-event-" + position, event, previousHash, position);
    }

    private PolicyTrustChangeAuditDocument sourceEvent(String id) {
        return new PolicyTrustChangeAuditDocument(id, "source-request-" + id, "APPROVE",
                "governance-actor", "c".repeat(64), "a".repeat(64), "proof-" + id, NOW);
    }
}
