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
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        when(fixture.repository.findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc()).thenReturn(List.of(published));

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isEqualTo(1);
        assertThat(published.isProjectionMismatch()).isFalse();
        assertThat(published.getProjectionMismatchReason()).isNull();
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(com.frauddetection.alert.persistence.AlertDocument.class));
        Document set = (Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(set.get("decisionOutboxStatus")).isEqualTo(DecisionOutboxStatus.PUBLISHED);
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
        private final OutboxRecoveryService service = new OutboxRecoveryService(
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
