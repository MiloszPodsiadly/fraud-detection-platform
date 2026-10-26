package com.frauddetection.alert.governance.audit;

import com.frauddetection.alert.audit.outbox.WriteActionAuditOutboxRecord;
import com.frauddetection.alert.governance.modelpromotion.PolicyTrustChangeAuditDocument;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIf(value = "#{T(org.testcontainers.DockerClientFactory).instance().isDockerAvailable()}", loadContext = false)
@Tag("integration")
class GovernanceEventCommitmentMongoIntegrationTest {
    @Test void businessEventAndAuditObligationCommitOrRollbackTogether() throws Exception {
        if (!FraudPlatformContainers.mongodb().isRunning()) FraudPlatformContainers.mongodb().start();
        var factory = new SimpleMongoClientDatabaseFactory(FraudPlatformContainers.mongodb()
                .getReplicaSetUrl("governance_commitment_" + UUID.randomUUID().toString().replace("-", "")));
        var mongo = new MongoTemplate(factory);
        var tx = new TransactionTemplate(new MongoTransactionManager(factory));
        var event = new PolicyTrustChangeAuditDocument("event-1", "request-1", "APPROVE",
                "actor-1", "a".repeat(64), "b".repeat(64), "proof-1",
                Instant.parse("2026-10-24T12:00:00.123456789Z"));
        String outboxId = GovernanceEventCommitment.idFor(GovernanceEventCommitment.Kind.TRUST_CHANGE, event.id());
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
                mongo.insert(event);
                GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.TRUST_CHANGE,
                        event.id(), event, "CURRENT_INSTITUTION/TEST", event.actorId(), event.requestId(),
                        "operation-1", event.assertionSha256(), event.requestId(), event.occurredAt());
                throw new IllegalStateException("force rollback");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(mongo.findById(event.id(), PolicyTrustChangeAuditDocument.class)).isNull();
            assertThat(mongo.findById(outboxId, WriteActionAuditOutboxRecord.class)).isNull();

            tx.executeWithoutResult(ignored -> {
                mongo.insert(event);
                GovernanceEventCommitment.record(mongo, GovernanceEventCommitment.Kind.TRUST_CHANGE,
                        event.id(), event, "CURRENT_INSTITUTION/TEST", event.actorId(), event.requestId(),
                        "operation-1", event.assertionSha256(), event.requestId(), event.occurredAt());
            });
            var stored = mongo.findById(event.id(), PolicyTrustChangeAuditDocument.class);
            var obligation = mongo.findById(outboxId, WriteActionAuditOutboxRecord.class);
            assertThat(stored).isNotNull();
            assertThat(obligation).isNotNull();
            assertThat(GovernanceEventCommitment.hash(GovernanceEventCommitment.Kind.TRUST_CHANGE,
                    stored.id(), stored, obligation.getGovernanceScope(), obligation.getActor(),
                    obligation.getGovernanceObjectId(), obligation.getGovernanceOperationId(),
                    obligation.getGovernanceProofDigest(), obligation.getGovernanceResultId(),
                    obligation.getGovernanceOccurredAt())).isEqualTo(obligation.getGovernanceEvidenceHash());
        } finally {
            mongo.getDb().drop();
            factory.destroy();
        }
    }
}
