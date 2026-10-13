package com.frauddetection.alert.outbox;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionalOutboxPersistedDocumentValidatorTest {

    @Test
    void acceptsCurrentPendingOutboxDocumentShape() {
        Document source = new Document("_id", "event-1")
                .append("status", TransactionalOutboxStatus.PENDING.name())
                .append("projection_revision", 0L)
                .append("attempts", 0);

        assertThat(TransactionalOutboxPersistedDocumentValidator.outboxViolations(source)).isEmpty();
    }

    @Test
    void reportsPersistedOutboxShapeViolationsWithoutMongoAccess() {
        Document source = new Document("status", "RETIRED")
                .append("projection_revision", -1L)
                .append("attempts", -1)
                .append("resolution_reason", "retired");

        assertThat(TransactionalOutboxPersistedDocumentValidator.outboxViolations(source)).contains(
                "STATUS_UNSUPPORTED",
                "PROJECTION_REVISION_MISSING_OR_INVALID",
                "ATTEMPTS_MISSING_OR_INVALID",
                "RETIRED_RESOLUTION_REASON_PRESENT"
        );
    }

    @Test
    void reportsProjectionRelationshipViolationsWithoutMutatingDocuments() {
        Document source = new Document("_id", "event-1")
                .append("resource_id", "alert-1")
                .append("projection_revision", 1L);
        Document alert = new Document("_id", "alert-2")
                .append("decisionOutboxEventId", "event-2")
                .append("decisionOutboxProjectionRevision", 2L);
        Document sourceBefore = new Document(source);
        Document alertBefore = new Document(alert);

        assertThat(TransactionalOutboxPersistedDocumentValidator.relationshipViolations(source, alert)).contains(
                "ALERT_EVENT_ID_DOES_NOT_MATCH_SOURCE",
                "ALERT_PROJECTION_NEWER_THAN_SOURCE",
                "ALERT_RESOURCE_ID_DOES_NOT_MATCH_SOURCE"
        );
        assertThat(source).isEqualTo(sourceBefore);
        assertThat(alert).isEqualTo(alertBefore);
    }
}
