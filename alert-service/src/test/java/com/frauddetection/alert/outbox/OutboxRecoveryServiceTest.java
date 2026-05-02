package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRecoveryServiceTest {

    @Test
    void shouldReleaseStaleProcessingToRetryableButMarkStalePublishAttemptedUnknown() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument processing = record("processing-1", TransactionalOutboxStatus.PROCESSING);
        TransactionalOutboxRecordDocument attempted = record("attempted-1", TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(eq(TransactionalOutboxStatus.PROCESSING), any()))
                .thenReturn(List.of(processing));
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(eq(TransactionalOutboxStatus.PUBLISH_ATTEMPTED), any()))
                .thenReturn(List.of(attempted));
        when(fixture.repository.findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc()).thenReturn(List.of());

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.releasedStaleProcessing()).isEqualTo(1);
        assertThat(response.publishAttemptedMarkedUnknown()).isEqualTo(1);
        assertThat(processing.getStatus()).isEqualTo(TransactionalOutboxStatus.FAILED_RETRYABLE);
        assertThat(attempted.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(attempted.getLastError()).isEqualTo("STALE_PUBLISH_ATTEMPT_CONFIRMATION_UNKNOWN");
        verify(fixture.publisherCoordinator).publishPending(100);
    }

    @Test
    void shouldRepairProjectionMismatchFromAuthoritativeOutboxRecord() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = record("event-1", TransactionalOutboxStatus.PUBLISHED);
        record.setProjectionMismatch(true);
        record.setProjectionMismatchReason("ALERT_PROJECTION_UPDATE_FAILED");
        when(fixture.repository.findTop100ByProjectionMismatchTrueOrderByCreatedAtAsc()).thenReturn(List.of(record));

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isEqualTo(1);
        assertThat(record.isProjectionMismatch()).isFalse();
        assertThat(record.getProjectionMismatchReason()).isNull();
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(com.frauddetection.alert.persistence.AlertDocument.class));
        org.bson.Document set = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(set.get("decisionOutboxStatus")).isEqualTo(DecisionOutboxStatus.PUBLISHED);
    }

    @Test
    void shouldRouteManualConfirmationResolutionThroughRegulatedCoordinator() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = record("event-1", TransactionalOutboxStatus.PUBLISHED);
        when(fixture.regulatedMutationCoordinator.commit(any())).thenReturn(new RegulatedMutationResult<>(
                RegulatedMutationState.EVIDENCE_PENDING,
                OutboxRecordResponse.from(record)
        ));
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));

        TransactionalOutboxRecordDocument response = fixture.service.resolveConfirmation(
                "event-1",
                request(),
                "ops-admin",
                "outbox-confirm-event-1"
        );

        assertThat(response.getEventId()).isEqualTo("event-1");
        verify(fixture.regulatedMutationCoordinator).commit(argThat(command ->
                "outbox-confirm-event-1".equals(command.idempotencyKey())
                        && "event-1".equals(command.resourceId())));
    }

    private OutboxConfirmationResolutionRequest request() {
        return new OutboxConfirmationResolutionRequest(
                OutboxConfirmationResolution.PUBLISHED,
                "broker offset verified",
                new ResolutionEvidenceReference(
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "topic=fraud-decisions,partition=0,offset=42",
                        Instant.parse("2026-05-02T10:00:00Z"),
                        "ops-admin"
                )
        );
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
        record.setCreatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-05-02T10:00:00Z"));
        record.setLeaseExpiresAt(Instant.parse("2026-05-02T10:01:00Z"));
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
