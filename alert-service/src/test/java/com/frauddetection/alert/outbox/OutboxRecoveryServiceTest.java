package com.frauddetection.alert.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.outbox.OutboxConfirmationResolutionMutationHandler;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxRecoveryServiceTest {

    @ParameterizedTest
    @EnumSource(value = TransactionalOutboxStatus.class, names = {
            "PUBLISHED",
            "PUBLISH_CONFIRMATION_UNKNOWN",
            "FAILED_RETRYABLE",
            "FAILED_TERMINAL",
            "RECOVERY_REQUIRED"
    })
    void projectionMismatchIsClearedOnlyAfterSuccessfulProjectionWrite(TransactionalOutboxStatus status) {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = mismatchedRecord(status);
        stubOutdatedAlert(fixture, record);
        when(fixture.repository.findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(record));
        List<String> writeOrder = new ArrayList<>();
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenAnswer(invocation -> {
            Update update = invocation.getArgument(1);
            Document updateObject = update.getUpdateObject();
            Document unset = (Document) updateObject.get("$unset");
            writeOrder.add(unset != null && unset.containsKey("projection_mismatch") ? "CLEAR" : "CLAIM");
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

        assertThat(response.projectionRepaired()).isOne();
        assertThat(writeOrder).containsExactly("CLAIM", "PROJECT", "CLEAR");
    }

    @ParameterizedTest
    @EnumSource(value = TransactionalOutboxStatus.class, names = {
            "PUBLISHED",
            "PUBLISH_CONFIRMATION_UNKNOWN",
            "FAILED_RETRYABLE",
            "FAILED_TERMINAL",
            "RECOVERY_REQUIRED"
    })
    void projectionMismatchRemainsWhenProjectionWriteFails(TransactionalOutboxStatus status) {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = mismatchedRecord(status);
        stubOutdatedAlert(fixture, record);
        when(fixture.repository.findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(record));
        List<Update> outboxUpdates = new ArrayList<>();
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenAnswer(invocation -> {
            outboxUpdates.add(invocation.getArgument(1));
            return UpdateResult.acknowledged(1, 1L, null);
        });
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(com.frauddetection.alert.persistence.AlertDocument.class)
        )).thenReturn(UpdateResult.acknowledged(0, 0L, null));

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isZero();
        assertThat(outboxUpdates).hasSize(2);
        assertThat(outboxUpdates)
                .noneMatch(update -> {
                    Document unset = (Document) update.getUpdateObject().get("$unset");
                    return unset != null && unset.containsKey("projection_mismatch");
                });
        Document retained = (Document) outboxUpdates.get(1).getUpdateObject().get("$set");
        assertThat(retained.get("projection_mismatch")).isEqualTo(true);
        assertThat(retained.getString("projection_mismatch_reason"))
                .isEqualTo("ALERT_PROJECTION_REPAIR_FAILED");
    }

    @Test
    void shouldReleaseStaleProcessingToRetryableButMarkStalePublishAttemptedUnknown() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument processing = record("processing-1", TransactionalOutboxStatus.PROCESSING);
        TransactionalOutboxRecordDocument attempted = record("attempted-1", TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(eq(TransactionalOutboxStatus.PROCESSING), any()))
                .thenReturn(List.of(processing));
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(eq(TransactionalOutboxStatus.PUBLISH_ATTEMPTED), any()))
                .thenReturn(List.of(attempted));
        when(fixture.repository
                .findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(
                        any()
                )).thenReturn(List.of());

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.releasedStaleProcessing()).isEqualTo(1);
        assertThat(response.publishAttemptedMarkedUnknown()).isEqualTo(1);
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
        stubOutdatedAlert(fixture, record);
        when(fixture.repository
                .findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(
                        any()
                )).thenReturn(List.of(record));

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isEqualTo(1);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(com.frauddetection.alert.persistence.AlertDocument.class));
        org.bson.Document set = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(set.get("decisionOutboxStatus")).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        ArgumentCaptor<Update> outboxUpdateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate, times(2)).updateFirst(
                any(Query.class),
                outboxUpdateCaptor.capture(),
                eq(TransactionalOutboxRecordDocument.class)
        );
        Document clear = (Document) outboxUpdateCaptor.getAllValues().get(1).getUpdateObject().get("$unset");
        assertThat(clear).containsKeys("projection_mismatch", "projection_mismatch_reason");
    }

    @Test
    void dueReconciliationRepairsOutdatedProjectionWithoutMismatchMarker() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = record("event-due", TransactionalOutboxStatus.PUBLISHED);
        record.setProjectionRevision(4L);
        record.setProjectionReconcileAfter(Instant.parse("2026-05-02T10:01:00Z"));
        stubOutdatedAlert(fixture, record);
        when(fixture.repository
                .findTop100ByStatusInAndProjectionReconcileAfterLessThanEqualOrderByProjectionReconcileAfterAscCreatedAtAsc(
                        any(),
                        any()
                ))
                .thenReturn(List.of(record));

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isOne();
        verify(fixture.mongoTemplate).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(com.frauddetection.alert.persistence.AlertDocument.class)
        );
    }

    @Test
    void missingResourceIdRemainsObservableAndIsRetriedWithoutFabricatingAlert() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = record("event-no-resource", TransactionalOutboxStatus.PUBLISHED);
        record.setResourceId("   ");
        record.setProjectionReconcileAfter(Instant.parse("2026-05-02T10:01:00Z"));
        when(fixture.repository
                .findTop100ByStatusInAndProjectionReconcileAfterLessThanEqualOrderByProjectionReconcileAfterAscCreatedAtAsc(
                        any(),
                        any()
                ))
                .thenReturn(List.of(record));
        ArgumentCaptor<Update> outboxUpdates = ArgumentCaptor.forClass(Update.class);

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.projectionRepaired()).isZero();
        verify(fixture.mongoTemplate, times(2)).updateFirst(
                any(Query.class),
                outboxUpdates.capture(),
                eq(TransactionalOutboxRecordDocument.class)
        );
        Document retained = outboxUpdates.getAllValues().get(1).getUpdateObject().get("$set", Document.class);
        assertThat(retained.getString("projection_mismatch_reason"))
                .isEqualTo("ALERT_PROJECTION_RESOURCE_ID_MISSING");
        assertThat(retained.get("projection_reconcile_after")).isNotNull();
        verify(fixture.mongoTemplate, never()).findById(any(), eq(com.frauddetection.alert.persistence.AlertDocument.class));
        verify(fixture.mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(com.frauddetection.alert.persistence.AlertDocument.class)
        );
    }

    @Test
    void staleRecoveryWorkerDoesNotReportOrProjectLostCas() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument processing = record("processing-1", TransactionalOutboxStatus.PROCESSING);
        TransactionalOutboxRecordDocument attempted = record("attempted-1", TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq(TransactionalOutboxStatus.PROCESSING),
                any()
        )).thenReturn(List.of(processing));
        when(fixture.repository.findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
                eq(TransactionalOutboxStatus.PUBLISH_ATTEMPTED),
                any()
        )).thenReturn(List.of(attempted));
        when(fixture.repository.findTop100ByStatusInAndProjectionMismatchTrueAndProjectionReconcileAfterIsNullOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenReturn(UpdateResult.acknowledged(1, 0L, null));

        OutboxRecoveryRunResponse response = fixture.service.recoverNow();

        assertThat(response.releasedStaleProcessing()).isZero();
        assertThat(response.publishAttemptedMarkedUnknown()).isZero();
        assertThat(processing.getStatus()).isEqualTo(TransactionalOutboxStatus.PROCESSING);
        assertThat(attempted.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_ATTEMPTED);
        verify(fixture.publisherCoordinator, never()).updateAlertProjection(any(), any(), any(), any());
    }

    @Test
    void shouldRouteManualConfirmationResolutionThroughRegulatedCoordinator() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = record("event-1", TransactionalOutboxStatus.PUBLISHED);
        when(fixture.regulatedMutationCoordinator.commit(any())).thenReturn(new RegulatedMutationResult<>(
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                OutboxRecordResponse.from(record)
        ));
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));

        OutboxRecordResponse response = fixture.service.resolveConfirmation(
                "event-1",
                request(),
                "ops-admin",
                "outbox-confirm-event-1"
        );

        assertThat(response.eventId()).isEqualTo("event-1");
        assertThat(response.status()).isEqualTo(TransactionalOutboxStatus.PUBLISHED.name());
        assertThat(response.operationStatus())
                .isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name());
        verify(fixture.regulatedMutationCoordinator).commit(argThat(command ->
                "outbox-confirm-event-1".equals(command.idempotencyKey())
                        && "event-1".equals(command.resourceId())
                        && command.action() == AuditAction.RESOLVE_TRANSACTIONAL_OUTBOX_CONFIRMATION
                        && command.mutationModelVersion() == RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1
                        && command.intent() != null));
    }

    @Test
    void idempotentReplayPreservesSuccessfulOutboxResponse() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument record = record("event-1", TransactionalOutboxStatus.PUBLISHED);
        record.setLastError("recorded-error");
        record.setPublishedAt(Instant.parse("2026-05-02T10:30:00Z"));
        record.setConfirmationUnknownAt(Instant.parse("2026-05-02T10:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-05-02T10:45:00Z"));
        record.setResolutionPending(true);
        record.setResolutionControlMode("DUAL_CONTROL_REQUESTED");
        record.setResolutionRequestId("pending-request-1");
        record.setResolutionProposedOutcome("PUBLISHED");
        record.setResolutionRequestedBy("ops-requester");
        record.setResolutionRequestedAt(Instant.parse("2026-05-02T10:15:00Z"));
        record.setResolutionApprovedBy("ops-approver");
        record.setResolutionApprovedAt(Instant.parse("2026-05-02T10:25:00Z"));
        AtomicReference<RegulatedMutationResponseSnapshot> persistedSnapshot = new AtomicReference<>();
        when(fixture.regulatedMutationCoordinator.commit(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RegulatedMutationCommand<TransactionalOutboxRecordDocument, OutboxRecordResponse> command =
                    invocation.getArgument(0);
            OutboxRecordResponse response;
            if (persistedSnapshot.get() == null) {
                response = OutboxRecordResponse.from(record);
                persistedSnapshot.set(command.responseSnapshotter().snapshot(response));
            } else {
                response = command.responseRestorer().restore(persistedSnapshot.get());
            }
            return new RegulatedMutationResult<>(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL, response);
        });

        OutboxRecordResponse first = fixture.service.resolveConfirmation(
                "event-1", request(), "ops-admin", "outbox-confirm-event-1");
        OutboxRecordResponse replayed = fixture.service.resolveConfirmation(
                "event-1", request(), "ops-admin", "outbox-confirm-event-1");

        assertThat(replayed).isEqualTo(first);
        assertThat(replayed.resolutionRequestId()).isEqualTo("pending-request-1");
        assertThat(replayed.resolutionProposedOutcome()).isEqualTo("PUBLISHED");
        assertThat(replayed.confirmationUnknownAt()).isNotEqualTo(replayed.resolutionRequestedAt());
    }

    @Test
    void shouldRejectMissingAuthenticatedActorBeforeCreatingRegulatedCommand() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.resolveConfirmation(
                "event-1",
                request(),
                "   ",
                "outbox-confirm-event-1"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("authenticated actor");

        verifyNoInteractions(fixture.regulatedMutationCoordinator, fixture.resolutionMutationHandler);
    }

    @Test
    void inProgressMutationDoesNotExposeExistingOutboxRecordAsResolved() {
        Fixture fixture = new Fixture();
        TransactionalOutboxRecordDocument existing = record(
                "event-1",
                TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN
        );
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(existing));
        when(fixture.regulatedMutationCoordinator.commit(any())).thenAnswer(invocation -> {
            com.frauddetection.alert.regulated.RegulatedMutationCommand<?, OutboxRecordResponse> command =
                    invocation.getArgument(0);
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.FINALIZING,
                    command.statusResponseFactory().response(RegulatedMutationState.FINALIZING)
            );
        });

        OutboxRecordResponse response = fixture.service.resolveConfirmation(
                "event-1",
                request(),
                "ops-admin",
                "outbox-confirm-event-1"
        );

        assertThat(response.eventId()).isEqualTo("event-1");
        assertThat(response.status()).isNull();
        assertThat(response.operationStatus()).isEqualTo(RegulatedMutationState.FINALIZING.name());
        verify(fixture.repository, never()).findById("event-1");
    }

    private OutboxConfirmationResolutionRequest request() {
        return new OutboxConfirmationResolutionRequest(
                OutboxConfirmationResolution.PUBLISHED,
                null,
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

    private TransactionalOutboxRecordDocument mismatchedRecord(TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument record = record("event-" + status.name().toLowerCase(), status);
        record.setProjectionMismatch(true);
        record.setProjectionMismatchReason("ALERT_PROJECTION_UPDATE_FAILED");
        record.setLastError(status == TransactionalOutboxStatus.PUBLISHED ? null : "source-error");
        record.setPublishedAt(Instant.parse("2026-05-02T10:30:00Z"));
        return record;
    }

    private void stubOutdatedAlert(Fixture fixture, TransactionalOutboxRecordDocument record) {
        com.frauddetection.alert.persistence.AlertDocument alert =
                new com.frauddetection.alert.persistence.AlertDocument();
        alert.setAlertId(record.getResourceId());
        alert.setDecisionOutboxEventId(record.getEventId());
        alert.setDecisionOutboxProjectionRevision(record.getProjectionRevision() - 1L);
        alert.setDecisionOutboxStatus(DecisionOutboxStatus.PENDING);
        when(fixture.mongoTemplate.findById(
                record.getResourceId(),
                com.frauddetection.alert.persistence.AlertDocument.class
        )).thenReturn(alert);
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
