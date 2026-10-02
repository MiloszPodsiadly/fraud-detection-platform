package com.frauddetection.alert.regulated;

import com.frauddetection.common.testsupport.base.AbstractIntegrationTest;
import com.frauddetection.common.testsupport.container.FraudPlatformContainers;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Tag("invariant-proof")
class RegulatedMutationDecisionIndexIntegrationTest extends AbstractIntegrationTest {

    private SimpleMongoClientDatabaseFactory databaseFactory;
    private MongoTemplate mongoTemplate;
    private RegulatedMutationDecisionIndexStartupGuard guard;

    @BeforeEach
    void setUp() {
        String databaseName = "decision_index_" + UUID.randomUUID().toString().replace("-", "");
        databaseFactory = new SimpleMongoClientDatabaseFactory(
                FraudPlatformContainers.mongodb().getReplicaSetUrl(databaseName)
        );
        mongoTemplate = new MongoTemplate(databaseFactory);
        guard = new RegulatedMutationDecisionIndexStartupGuard(mongoTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mongoTemplate != null) {
            mongoTemplate.getDb().drop();
        }
        if (databaseFactory != null) {
            databaseFactory.destroy();
        }
    }

    @Test
    void autoIndexCreationDisabledAndMissingIndexFailsClosed() {
        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INDEX_MISSING");
    }

    @Test
    void canonicalIndexIsAcceptedWithoutAutoIndexCreation() {
        createCanonicalIndex();

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    void inconsistentHistoricalOwnershipCannotPassRolloutValidation() {
        createCanonicalIndex();
        MongoCollectionFixture.insertCommandWithoutOwnership(mongoTemplate, "command-1", "alert-1");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OWNERSHIP_DATA_INVALID");
    }

    @Test
    void releasedPreCommitCommandWithSuccessAuditIdFailsClosed() {
        createCanonicalIndex();
        mongoTemplate.getCollection(RegulatedMutationDecisionIndexStartupGuard.COLLECTION).insertOne(
                releasedPreCommitCommand("command-ambiguous", "alert-ambiguous")
                        .append("success_audit_id", "audit-success-1")
                        .append("success_audit_recorded", false)
        );

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OWNERSHIP_DATA_INVALID");
    }

    @Test
    void canonicalReleasedAndCommittedOwnershipRecordsAreAccepted() {
        createCanonicalIndex();
        mongoTemplate.getCollection(RegulatedMutationDecisionIndexStartupGuard.COLLECTION).insertOne(
                releasedPreCommitCommand("command-rejected", "alert-reusable")
        );
        mongoTemplate.getCollection(RegulatedMutationDecisionIndexStartupGuard.COLLECTION).insertOne(
                new Document("_id", "command-committed")
                        .append("resource_id", "alert-owned")
                        .append("resource_type", "ALERT")
                        .append("action", "SUBMIT_ANALYST_DECISION")
                        .append("state", RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name())
                        .append("decision_slot_claimed", true)
                        .append("success_audit_id", "audit-success-2")
                        .append("success_audit_recorded", true)
        );

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    private void createCanonicalIndex() {
        mongoTemplate.getCollection(RegulatedMutationDecisionIndexStartupGuard.COLLECTION).createIndex(
                Document.parse(RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_KEYS),
                new IndexOptions()
                        .name(RegulatedMutationCommandDocument.DECISION_SLOT_INDEX_NAME)
                        .unique(true)
                        .partialFilterExpression(Document.parse(
                                RegulatedMutationCommandDocument.DECISION_SLOT_PARTIAL_FILTER
                        ))
        );
    }

    private Document releasedPreCommitCommand(String commandId, String alertId) {
        return new Document("_id", commandId)
                .append("resource_id", alertId)
                .append("resource_type", "ALERT")
                .append("action", "SUBMIT_ANALYST_DECISION")
                .append("state", RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE.name())
                .append("decision_slot_claimed", false);
    }

    private static final class MongoCollectionFixture {
        private static void insertCommandWithoutOwnership(
                MongoTemplate mongoTemplate,
                String commandId,
                String alertId
        ) {
            mongoTemplate.getCollection(RegulatedMutationDecisionIndexStartupGuard.COLLECTION).insertOne(
                    new Document("_id", commandId)
                            .append("resource_id", alertId)
                            .append("resource_type", "ALERT")
                            .append("action", "SUBMIT_ANALYST_DECISION")
                            .append("state", RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name())
            );
        }
    }
}
