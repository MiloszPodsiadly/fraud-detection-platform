package com.frauddetection.alert.outbox.failure;

import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.OutboxPublisherCoordinator;
import com.frauddetection.alert.outbox.OutboxRecoveryRunResponse;
import com.frauddetection.alert.outbox.OutboxRecoveryService;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("failure-injection")
@Tag("invariant-proof")
class TransactionalOutboxFailureInjectionTest {

    @Test
    void shouldConvertStalePublishAttemptToConfirmationUnknownAndNotPublished() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument attempted = record("event-1", TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq(TransactionalOutboxStatus.PROCESSING),
                any()
        )).thenReturn(List.of());
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq(TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                any()
        )).thenReturn(List.of(attempted));
        when(fixture.repository.findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc()).thenReturn(List.of());

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.publishAttemptedMarkedUnknown()).isEqualTo(1);
        assertThat(attempted.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(attempted.getStatus()).isNotEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(attempted.getLastError()).isEqualTo("STALE_PUBLISH_ATTEMPT_CONFIRMATION_UNKNOWN");
        verify(fixture.publisherCoordinator).publishPending(100);
    }

    @Test
    void shouldRepairProjectionMismatchFromAuthoritativeOutboxRecordOnly() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument published = record("event-2", TransactionalOutboxStatus.PUBLISHED);
        published.setProjectionMismatch(true);
        published.setProjectionMismatchReason("ALERT_PROJECTION_UPDATE_FAILED");
        published.setPublishedAt(Instant.parse("2026-05-03T00:05:00Z"));
        com.frauddetection.alert.persistence.AlertDocument outdated =
                new com.frauddetection.alert.persistence.AlertDocument();
        outdated.setAlertId(published.getResourceId());
        outdated.setDecisionOutboxEventId(published.getEventId());
        outdated.setDecisionOutboxProjectionRevision(published.getProjectionRevision() - 1L);
        outdated.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        when(fixture.mongoTemplate.findById(
                published.getResourceId(),
                com.frauddetection.alert.persistence.AlertDocument.class
        )).thenReturn(outdated);
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        when(fixture.repository.findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc()).thenReturn(List.of(published));
        List<String> writeOrder = new ArrayList<>();
        List<Query> outboxQueries = new ArrayList<>();
        List<Update> outboxUpdates = new ArrayList<>();
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            Update update = invocation.getArgument(1);
            Document unset = (Document) update.getUpdateObject().get("$unset");
            writeOrder.add(unset != null && unset.containsKey("projection_mismatch") ? "CLEAR" : "CLAIM");
            outboxQueries.add(query);
            outboxUpdates.add(update);
            return UpdateResult.acknowledged(1, 1L, null);
        });
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(com.frauddetection.alert.persistence.AlertDocument.class)
        )).thenAnswer(invocation -> {
            writeOrder.add("PROJECT");
            return UpdateResult.acknowledged(1, 1L, null);
        });

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isEqualTo(1);
        assertThat(writeOrder).containsExactly("CLAIM", "PROJECT", "CLEAR");
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(com.frauddetection.alert.persistence.AlertDocument.class));
        Document set = (Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(set.get("decisionOutboxStatus")).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        assertThat(outboxUpdates).hasSize(2);
        Document claimSet = (Document) outboxUpdates.get(0).getUpdateObject().get("$set");
        String repairToken = claimSet.getString("projection_repair_token");
        assertThat(repairToken).isNotBlank();
        Document clearUnset = (Document) outboxUpdates.get(1).getUpdateObject().get("$unset");
        assertThat(clearUnset).containsKeys(
                "projection_mismatch",
                "projection_mismatch_reason",
                "projection_reconcile_after",
                "projection_repair_token",
                "projection_repair_claimed_at"
        );
        List<Document> clearConditions = outboxQueries.get(1).getQueryObject().getList("$and", Document.class);
        assertThat(clearConditions)
                .anySatisfy(condition -> assertThat(condition).containsEntry("_id", published.getEventId()))
                .anySatisfy(condition -> assertThat(condition).containsEntry("status", published.getStatus()))
                .anySatisfy(condition -> assertThat(condition)
                        .containsEntry("projection_revision", published.getProjectionRevision()))
                .anySatisfy(condition -> assertThat(condition).containsEntry("projection_repair_token", repairToken));
    }

    private TransactionalOutboxRecordDocument record(String eventId, TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setEventId(eventId);
        record.setDedupeKey(eventId);
        record.setMutationCommandId("command-" + eventId);
        record.setResourceType("ALERT");
        record.setResourceId("alert-1");
        record.setEventType("FRAUD_DECISION");
        record.setPayloadHash("payload-hash");
        record.setStatus(status);
        record.setCreatedAt(Instant.parse("2026-05-03T00:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-05-03T00:00:00Z"));
        record.setLeaseExpiresAt(Instant.parse("2026-05-03T00:01:00Z"));
        record.setAttempts(1);
        return record;
    }

    private static final class Fixture {
        private final TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final OutboxPublisherCoordinator publisherCoordinator = mock(OutboxPublisherCoordinator.class);
        private final RegulatedMutationCoordinator regulatedMutationCoordinator = mock(RegulatedMutationCoordinator.class);
        private final OutboxConfirmationResolutionMutationHandler resolutionMutationHandler = mock(OutboxConfirmationResolutionMutationHandler.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final OutboxRecoveryService service;

        private Fixture() {
            when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TransactionalOutboxRecordDocument.class)))
                    .thenReturn(UpdateResult.acknowledged(1, 1L, null));
            when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(com.frauddetection.alert.persistence.AlertDocument.class)))
                    .thenReturn(UpdateResult.acknowledged(1, 1L, null));
            service = new OutboxRecoveryService(
                    repository,
                    mongoTemplate,
                    publisherCoordinator,
                    regulatedMutationCoordinator,
                    resolutionMutationHandler,
                    metrics,
                    Duration.ofMinutes(2)
            );
        }
    }
}
