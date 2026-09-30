package com.frauddetection.alert.outbox;

import com.frauddetection.alert.messaging.FraudDecisionEventPublisher;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPublisherCoordinatorProjectionTest {

    @Test
    void clearsExistingMismatchOnlyAfterSuccessfulAlertProjection() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        List<String> writeOrder = new ArrayList<>();
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenAnswer(invocation -> {
                    writeOrder.add("PROJECT");
                    return UpdateResult.acknowledged(1, 1L, null);
                });
        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenAnswer(invocation -> {
            writeOrder.add("CLEAR");
            Update update = invocation.getArgument(1);
            Document unset = (Document) update.getUpdateObject().get("$unset");
            assertThat(unset).containsKeys("projection_mismatch", "projection_mismatch_reason");
            return UpdateResult.acknowledged(1, 1L, null);
        });
        OutboxPublisherCoordinator coordinator = coordinator(mongoTemplate);

        coordinator.updateAlertProjection(
                mismatchedRecord(),
                DecisionOutboxStatus.PUBLISHED,
                null,
                Instant.parse("2026-09-30T10:30:00Z")
        );

        assertThat(writeOrder).containsExactly("PROJECT", "CLEAR");
    }

    @Test
    void projectionFailureRetainsMismatchForRecovery() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("projection unavailable"));
        List<Update> outboxUpdates = new ArrayList<>();
        when(mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenAnswer(invocation -> {
            outboxUpdates.add(invocation.getArgument(1));
            return UpdateResult.acknowledged(1, 1L, null);
        });
        OutboxPublisherCoordinator coordinator = coordinator(mongoTemplate);

        coordinator.updateAlertProjection(
                mismatchedRecord(),
                DecisionOutboxStatus.PUBLISHED,
                null,
                Instant.parse("2026-09-30T10:30:00Z")
        );

        assertThat(outboxUpdates).hasSize(1);
        Document set = (Document) outboxUpdates.getFirst().getUpdateObject().get("$set");
        Document unset = (Document) outboxUpdates.getFirst().getUpdateObject().get("$unset");
        assertThat(set.get("projection_mismatch")).isEqualTo(true);
        assertThat(unset).isNull();
    }

    private OutboxPublisherCoordinator coordinator(MongoTemplate mongoTemplate) {
        return new OutboxPublisherCoordinator(
                mock(FraudDecisionEventPublisher.class),
                mongoTemplate,
                mock(AlertServiceMetrics.class),
                Duration.ofMinutes(1),
                5
        );
    }

    private TransactionalOutboxRecordDocument mismatchedRecord() {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId("event-1");
        record.setResourceId("alert-1");
        record.setStatus(TransactionalOutboxStatus.PUBLISHED);
        record.setAttempts(2);
        record.setProjectionMismatch(true);
        record.setProjectionMismatchReason("ALERT_PROJECTION_UPDATE_FAILED");
        record.setUpdatedAt(Instant.parse("2026-09-30T10:30:00Z"));
        return record;
    }
}
