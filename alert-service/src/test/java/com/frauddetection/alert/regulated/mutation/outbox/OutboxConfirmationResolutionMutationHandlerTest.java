package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxStatus;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxConfirmationResolutionMutationHandlerTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-09-30T10:05:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-30T10:06:00Z");

    @Test
    void shouldRequireDistinctSecondActorForBankModeDualControl() {
        Fixture fixture = fixture(true, true);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument requested = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(requested.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(requested.isResolutionPending()).isTrue();
        assertThat(requested.getResolutionControlMode()).isEqualTo("DUAL_CONTROL_REQUESTED");

        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), "ops-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("distinct actor");

        TransactionalOutboxRecordDocument approved = fixture.handler.resolve("event-1", request(), "ops-2");

        assertThat(approved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(approved.isResolutionPending()).isFalse();
        assertThat(approved.getResolutionControlMode()).isEqualTo("DUAL_CONTROL_APPROVED");
        assertThat(approved.getResolutionApprovedBy()).isEqualTo("ops-2");
    }

    @Test
    void shouldUseExplicitSingleControlAttestationOutsideBankMode() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument resolved = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(resolved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(resolved.getResolutionControlMode()).isEqualTo("SINGLE_CONTROL_OPERATOR_ATTESTED");
    }

    @Test
    void shouldRejectSingleControlPublishedResolutionInBankMode() {
        Fixture fixture = fixture(true, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), "ops-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("bank mode requires dual-control");
    }

    @Test
    void shouldProjectDualControlRequestFromPersistedOutboxRecord() {
        Fixture fixture = fixture(true, true);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> {
            TransactionalOutboxRecordDocument saved = persistedCopy(invocation.getArgument(0));
            saved.setAttempts(5);
            saved.setResolutionRequestedAt(REQUESTED_AT);
            saved.setResolutionRequestedBy("persisted-requester");
            saved.setResolutionReason("persisted pending reason");
            saved.setResolutionEvidenceType("PERSISTED_EVIDENCE");
            saved.setResolutionEvidenceReference("persisted-pending-reference");
            saved.setResolutionEvidenceVerifiedAt(APPROVED_AT);
            saved.setResolutionEvidenceVerifiedBy("persisted-verifier");
            return saved;
        });
        when(fixture.mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument saved = fixture.handler.resolve("event-1", request(), "ops-1");

        Document set = projectedAlertUpdate(fixture).getUpdateObject().get("$set", Document.class);
        assertThat(set.getString("decisionOutboxStatus")).isEqualTo(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(set.getInteger("decisionOutboxAttempts")).isEqualTo(saved.getAttempts());
        assertThat(set.get("decisionOutboxResolutionPending")).isEqualTo(true);
        assertThat(set.get("decisionOutboxResolutionRequestedAt")).isEqualTo(saved.getResolutionRequestedAt());
        assertThat(set.getString("decisionOutboxResolutionRequestedBy")).isEqualTo(saved.getResolutionRequestedBy());
        assertResolutionMetadata(set, saved);
    }

    @Test
    void shouldProjectPublishedResolutionFromPersistedOutboxRecord() {
        assertResolvedProjection(
                OutboxConfirmationResolution.PUBLISHED,
                TransactionalOutboxStatus.PUBLISHED,
                DecisionOutboxStatus.PUBLISHED
        );
    }

    @Test
    void shouldProjectRecoveryRequiredResolutionFromPersistedOutboxRecord() {
        assertResolvedProjection(
                OutboxConfirmationResolution.RECOVERY_REQUIRED,
                TransactionalOutboxStatus.RECOVERY_REQUIRED,
                DecisionOutboxStatus.FAILED_TERMINAL
        );
    }

    @Test
    void shouldMarkPersistedOutboxRecordWhenAlertProjectionFails() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("projection unavailable"));
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument saved = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(saved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertProjectionMismatch(fixture, saved, "ALERT_PROJECTION_UPDATE_FAILED");
    }

    @Test
    void shouldMarkPersistedOutboxRecordWhenProjectionResourceIdIsMissing() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        record.setResourceId(null);
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.mongoTemplate.updateFirst(
                any(Query.class),
                any(Update.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument saved = fixture.handler.resolve("event-1", request(), "ops-1");

        assertProjectionMismatch(fixture, saved, "ALERT_PROJECTION_RESOURCE_ID_MISSING");
        verify(fixture.mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(AlertDocument.class)
        );
    }

    private void assertProjectionMismatch(
            Fixture fixture,
            TransactionalOutboxRecordDocument saved,
            String expectedReason
    ) {
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate).updateFirst(
                queryCaptor.capture(),
                updateCaptor.capture(),
                eq(TransactionalOutboxRecordDocument.class)
        );
        Document mismatch = updateCaptor.getValue().getUpdateObject().get("$set", Document.class);
        assertThat(mismatch)
                .containsEntry("projection_mismatch", true)
                .containsEntry("projection_mismatch_reason", expectedReason);
        assertThat(queryCaptor.getValue().getQueryObject().getList("$and", Document.class))
                .anySatisfy(condition -> assertThat(condition).containsEntry("_id", saved.getEventId()))
                .anySatisfy(condition -> assertThat(condition).containsEntry("status", saved.getStatus()))
                .anySatisfy(condition -> assertThat(condition).containsEntry("updated_at", saved.getUpdatedAt()));
    }

    private void assertResolvedProjection(
            OutboxConfirmationResolution resolution,
            TransactionalOutboxStatus expectedSourceStatus,
            String expectedProjectionStatus
    ) {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));
        when(fixture.repository.save(any())).thenAnswer(invocation -> {
            TransactionalOutboxRecordDocument saved = persistedCopy(invocation.getArgument(0));
            saved.setAttempts(7);
            saved.setResolutionApprovedAt(APPROVED_AT);
            saved.setResolutionApprovedBy("persisted-approver");
            saved.setResolutionReason("persisted resolution reason");
            saved.setResolutionEvidenceType("PERSISTED_EVIDENCE");
            saved.setResolutionEvidenceReference("persisted-resolution-reference");
            saved.setResolutionEvidenceVerifiedAt(REQUESTED_AT);
            saved.setResolutionEvidenceVerifiedBy("persisted-verifier");
            if (saved.getStatus() == TransactionalOutboxStatus.PUBLISHED) {
                saved.setPublishedAt(PUBLISHED_AT);
            }
            return saved;
        });
        when(fixture.mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument saved = fixture.handler.resolve(
                "event-1",
                request(resolution),
                "request-actor"
        );

        Update projected = projectedAlertUpdate(fixture);
        Document set = projected.getUpdateObject().get("$set", Document.class);
        Document unset = projected.getUpdateObject().get("$unset", Document.class);
        assertThat(saved.getStatus()).isEqualTo(expectedSourceStatus);
        assertThat(set.getString("decisionOutboxStatus")).isEqualTo(expectedProjectionStatus);
        assertThat(set.getInteger("decisionOutboxAttempts")).isEqualTo(saved.getAttempts());
        assertThat(set.get("decisionOutboxResolutionApprovedAt")).isEqualTo(saved.getResolutionApprovedAt());
        assertThat(set.getString("decisionOutboxResolutionApprovedBy")).isEqualTo(saved.getResolutionApprovedBy());
        assertResolutionMetadata(set, saved);
        assertThat(unset).containsKey("decisionOutboxResolutionPending");
        if (expectedSourceStatus == TransactionalOutboxStatus.PUBLISHED) {
            assertThat(set.get("decisionOutboxPublishedAt")).isEqualTo(saved.getPublishedAt());
            assertThat(unset).containsKeys("decisionOutboxLastError", "decisionOutboxFailureReason");
        } else {
            assertThat(set)
                    .containsEntry("decisionOutboxLastError", "MANUAL_RECOVERY_REQUIRED")
                    .containsEntry("decisionOutboxFailureReason", "MANUAL_RECOVERY_REQUIRED");
        }
    }

    private TransactionalOutboxRecordDocument persistedCopy(TransactionalOutboxRecordDocument source) {
        TransactionalOutboxRecordDocument saved = new TransactionalOutboxRecordDocument();
        saved.setEventId(source.getEventId());
        saved.setResourceId(source.getResourceId());
        saved.setStatus(source.getStatus());
        saved.setAttempts(source.getAttempts());
        saved.setLeaseOwner(source.getLeaseOwner());
        saved.setLeaseExpiresAt(source.getLeaseExpiresAt());
        saved.setLastError(source.getLastError());
        saved.setPublishedAt(source.getPublishedAt());
        saved.setConfirmationUnknownAt(source.getConfirmationUnknownAt());
        saved.setResolutionPending(source.isResolutionPending());
        saved.setResolutionControlMode(source.getResolutionControlMode());
        saved.setResolutionRequestedAt(source.getResolutionRequestedAt());
        saved.setResolutionRequestedBy(source.getResolutionRequestedBy());
        saved.setResolutionReason(source.getResolutionReason());
        saved.setResolutionEvidenceType(source.getResolutionEvidenceType());
        saved.setResolutionEvidenceReference(source.getResolutionEvidenceReference());
        saved.setResolutionEvidenceVerifiedAt(source.getResolutionEvidenceVerifiedAt());
        saved.setResolutionEvidenceVerifiedBy(source.getResolutionEvidenceVerifiedBy());
        saved.setResolutionApprovedAt(source.getResolutionApprovedAt());
        saved.setResolutionApprovedBy(source.getResolutionApprovedBy());
        saved.setUpdatedAt(source.getUpdatedAt());
        return saved;
    }

    private void assertResolutionMetadata(Document set, TransactionalOutboxRecordDocument saved) {
        assertThat(set.getString("decisionOutboxResolutionApprovalReason")).isEqualTo(saved.getResolutionReason());
        assertThat(set.getString("decisionOutboxResolutionEvidenceType")).isEqualTo(saved.getResolutionEvidenceType());
        assertThat(set.getString("decisionOutboxResolutionEvidenceReference"))
                .isEqualTo(saved.getResolutionEvidenceReference());
        assertThat(set.get("decisionOutboxResolutionEvidenceVerifiedAt"))
                .isEqualTo(saved.getResolutionEvidenceVerifiedAt());
        assertThat(set.getString("decisionOutboxResolutionEvidenceVerifiedBy"))
                .isEqualTo(saved.getResolutionEvidenceVerifiedBy());
    }

    private Update projectedAlertUpdate(Fixture fixture) {
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(fixture.mongoTemplate).updateFirst(
                any(Query.class),
                updateCaptor.capture(),
                eq(AlertDocument.class)
        );
        return updateCaptor.getValue();
    }

    private Fixture fixture(boolean bankMode, boolean dualControl) {
        TransactionalOutboxRecordRepository repository = mock(TransactionalOutboxRecordRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        return new Fixture(
                repository,
                mongoTemplate,
                new OutboxConfirmationResolutionMutationHandler(repository, mongoTemplate, bankMode, dualControl)
        );
    }

    private TransactionalOutboxRecordDocument record() {
        TransactionalOutboxRecordDocument document = new TransactionalOutboxRecordDocument();
        document.setEventId("event-1");
        document.setResourceId("alert-1");
        document.setStatus(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        return document;
    }

    private OutboxConfirmationResolutionRequest request() {
        return request(OutboxConfirmationResolution.PUBLISHED);
    }

    private OutboxConfirmationResolutionRequest request(OutboxConfirmationResolution resolution) {
        return new OutboxConfirmationResolutionRequest(
                resolution,
                "broker offset verified",
                new ResolutionEvidenceReference(
                        ResolutionEvidenceType.BROKER_OFFSET,
                        "topic=fraud-decisions,partition=0,offset=42",
                        Instant.parse("2026-05-02T10:00:00Z"),
                        "ops"
                )
        );
    }

    private record Fixture(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxConfirmationResolutionMutationHandler handler
    ) {
    }
}
