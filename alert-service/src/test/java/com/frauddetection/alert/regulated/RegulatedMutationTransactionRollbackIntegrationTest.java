package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("failure-injection")
@Tag("invariant-proof")
@Tag("integration")
class RegulatedMutationTransactionRollbackIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory mongoClientDatabaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationTransactionRunner runner;

    @BeforeEach
    void setUp() {
        String databaseName = "regulated_mutation_tx_" + UUID.randomUUID().toString().replace("-", "");
        mongoClientDatabaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(mongoClientDatabaseFactory);
        MongoTransactionManager transactionManager = new MongoTransactionManager(mongoClientDatabaseFactory);
        runner = new RegulatedMutationTransactionRunner(
                RegulatedMutationTransactionMode.REQUIRED,
                new TransactionTemplate(transactionManager)
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (mongoClientDatabaseFactory != null) {
            mongoClientDatabaseFactory.destroy();
        }
    }

    @Test
    void shouldRollbackBusinessMutationWhenOutboxWriteFailsInsideRequiredTransaction() {
        assertThatThrownBy(() -> runner.runLocalCommit(() -> {
            mongoTemplate.save(alert("alert-outbox-fail", AlertStatus.CLOSED));
            throw new IllegalStateException("outbox write failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), TransactionalOutboxRecordDocument.class)).isZero();
    }

    @Test
    void shouldRollbackBusinessAndOutboxWhenResponseSnapshotSaveFailsInsideRequiredTransaction() {
        assertThatThrownBy(() -> runner.runLocalCommit(() -> {
            mongoTemplate.save(alert("alert-snapshot-fail", AlertStatus.CLOSED));
            mongoTemplate.save(outbox("event-snapshot-fail", "alert-snapshot-fail"));
            throw new IllegalStateException("response snapshot save failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), TransactionalOutboxRecordDocument.class)).isZero();
    }

    @Test
    void shouldRollbackCommandTransitionBusinessAndOutboxWhenTransitionFailsInsideRequiredTransaction() {
        RegulatedMutationCommandDocument command = command("command-transition-fail", RegulatedMutationState.AUDIT_ATTEMPTED);
        mongoTemplate.save(command);

        assertThatThrownBy(() -> runner.runLocalCommit(() -> {
            command.setState(RegulatedMutationState.BUSINESS_COMMITTED);
            command.setUpdatedAt(Instant.parse("2026-05-02T10:01:00Z"));
            mongoTemplate.save(command);
            mongoTemplate.save(alert("alert-transition-fail", AlertStatus.CLOSED));
            mongoTemplate.save(outbox("event-transition-fail", "alert-transition-fail"));
            throw new IllegalStateException("command transition failed");
        })).isInstanceOf(IllegalStateException.class);

        RegulatedMutationCommandDocument restored = mongoTemplate.findById("command-transition-fail", RegulatedMutationCommandDocument.class);
        assertThat(restored).isNotNull();
        assertThat(restored.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), TransactionalOutboxRecordDocument.class)).isZero();
    }

    @Test
    void shouldRollbackCommandSnapshotBusinessAndOutboxWithoutSuccessAuditTruth() {
        RegulatedMutationCommandDocument command = command("command-local-boundary-fail", RegulatedMutationState.AUDIT_ATTEMPTED);
        mongoTemplate.save(command);

        assertThatThrownBy(() -> runner.runLocalCommit(() -> {
            command.setState(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
            command.setResponseSnapshot(new RegulatedMutationResponseSnapshot(
                    "alert-local-boundary-fail",
                    com.frauddetection.common.events.enums.AnalystDecision.CONFIRMED_FRAUD,
                    AlertStatus.RESOLVED,
                    "event-local-boundary-fail",
                    Instant.parse("2026-05-02T10:01:00Z"),
                    com.frauddetection.alert.api.SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
            ));
            command.setLocalCommitMarker("LOCAL_COMMITTED");
            command.setUpdatedAt(Instant.parse("2026-05-02T10:01:00Z"));
            mongoTemplate.save(command);
            mongoTemplate.save(alert("alert-local-boundary-fail", AlertStatus.CLOSED));
            mongoTemplate.save(outbox("event-local-boundary-fail", "alert-local-boundary-fail"));
            throw new IllegalStateException("snapshot persistence boundary failed");
        })).isInstanceOf(IllegalStateException.class);

        RegulatedMutationCommandDocument restored = mongoTemplate.findById("command-local-boundary-fail", RegulatedMutationCommandDocument.class);
        assertThat(restored).isNotNull();
        assertThat(restored.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(restored.getResponseSnapshot()).isNull();
        assertThat(restored.getLocalCommitMarker()).isNull();
        assertThat(restored.isSuccessAuditRecorded()).isFalse();
        assertThat(mongoTemplate.count(new Query(), AlertDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), TransactionalOutboxRecordDocument.class)).isZero();
        assertThat(mongoTemplate.count(new Query(), AuditEventDocument.class)).isZero();
    }

    private AlertDocument alert(String alertId, AlertStatus status) {
        AlertDocument document = new AlertDocument();
        document.setAlertId(alertId);
        document.setTransactionId(alertId + "-tx");
        document.setAlertStatus(status);
        document.setCreatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setDecidedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return document;
    }

    private TransactionalOutboxRecordDocument outbox(String eventId, String alertId) {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId(eventId);
        document.setDedupeKey(eventId);
        document.setMutationCommandId("command-" + eventId);
        document.setResourceType("ALERT");
        document.setResourceId(alertId);
        document.setEventType("FRAUD_DECISION");
        document.setPayloadHash("payload-hash");
        document.setStatus(TransactionalOutboxStatus.PENDING);
        document.setCreatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return document;
    }

    private RegulatedMutationCommandDocument command(String commandId, RegulatedMutationState state) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId(commandId);
        document.setIdempotencyKey(commandId + "-idem");
        document.setRequestHash("request-hash");
        document.setResourceId("alert-transition-fail");
        document.setResourceType("ALERT");
        document.setAction("SUBMIT_ANALYST_DECISION");
        document.setState(state);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        document.setCreatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        return document;
    }
}
