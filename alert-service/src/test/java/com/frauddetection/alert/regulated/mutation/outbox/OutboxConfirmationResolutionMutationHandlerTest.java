package com.frauddetection.alert.regulated.mutation.outbox;

import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.outbox.OutboxConfirmationResolution;
import com.frauddetection.alert.outbox.OutboxConfirmationResolutionRequest;
import com.frauddetection.alert.outbox.OutboxPublicationConfirmationProvenance;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxConfirmationResolutionMutationHandlerTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant APPROVED_AT = Instant.parse("2026-09-30T10:05:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-30T10:06:00Z");

    @Test
    void shouldRequireDistinctSecondActorForBankModeDualControl() {
        Fixture fixture = fixture(true, true);
        TransactionalOutboxRecordDocument record = record();
        mockPersistence(fixture, record);
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument requested = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(requested.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(requested.isResolutionPending()).isTrue();
        assertThat(requested.getResolutionControlMode()).isEqualTo("DUAL_CONTROL_REQUESTED");

        assertThat(requested.getResolutionRequestId()).isNotBlank();
        assertThat(requested.getResolutionProposedOutcome()).isEqualTo("PUBLISHED");

        assertThatThrownBy(() -> fixture.handler.resolve(
                "event-1",
                request(OutboxConfirmationResolution.PUBLISHED, requested.getResolutionRequestId()),
                "ops-1"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("distinct actor");

        TransactionalOutboxRecordDocument approved = fixture.handler.resolve(
                "event-1",
                request(OutboxConfirmationResolution.PUBLISHED, requested.getResolutionRequestId()),
                "ops-2"
        );

        assertThat(approved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(approved.isResolutionPending()).isFalse();
        assertThat(approved.getResolutionControlMode()).isEqualTo("DUAL_CONTROL_APPROVED");
        assertThat(approved.getResolutionApprovedBy()).isEqualTo("ops-2");
        assertThat(approved.getPublicationConfirmationProvenance())
                .isEqualTo(OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED);
    }

    @Test
    void shouldRejectApprovalThatChangesPublishedProposalToRecoveryRequired() {
        assertChangedProposalRejected(
                OutboxConfirmationResolution.PUBLISHED,
                OutboxConfirmationResolution.RECOVERY_REQUIRED
        );
    }

    @Test
    void shouldRejectApprovalThatChangesRecoveryRequiredProposalToPublished() {
        assertChangedProposalRejected(
                OutboxConfirmationResolution.RECOVERY_REQUIRED,
                OutboxConfirmationResolution.PUBLISHED
        );
    }

    @Test
    void shouldRejectApprovalForDifferentPendingRequest() {
        Fixture fixture = fixture(true, true);
        mockPersistence(fixture, record());
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        fixture.handler.resolve("event-1", request(), "requester");

        assertThatThrownBy(() -> fixture.handler.resolve(
                "event-1",
                request(OutboxConfirmationResolution.PUBLISHED, "different-request-id"),
                "approver"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("different pending request");
    }

    @Test
    void shouldRejectMissingOrBlankAuthenticatedActor() {
        Fixture fixture = fixture(true, true);

        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authenticated actor");
        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), "   "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authenticated actor");
        verifyNoInteractions(fixture.repository, fixture.mongoTemplate);
    }

    @Test
    void shouldPreserveRequesterEvidenceAndPersistSeparateApprovalEvidence() {
        Fixture fixture = fixture(true, true);
        mockPersistence(fixture, record());
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        ResolutionEvidenceReference requestEvidence = evidence("request-evidence", "request-verifier");
        ResolutionEvidenceReference approvalEvidence = evidence("approval-evidence", "approval-verifier");

        TransactionalOutboxRecordDocument requested = fixture.handler.resolve(
                "event-1",
                request(OutboxConfirmationResolution.PUBLISHED, null, "request reason", requestEvidence),
                "requester"
        );
        TransactionalOutboxRecordDocument approved = fixture.handler.resolve(
                "event-1",
                request(
                        OutboxConfirmationResolution.PUBLISHED,
                        requested.getResolutionRequestId(),
                        "approval reason",
                        approvalEvidence
                ),
                "approver"
        );

        assertThat(approved.getResolutionEvidenceReference()).isEqualTo("request-evidence");
        assertThat(approved.getResolutionEvidenceVerifiedBy()).isEqualTo("request-verifier");
        assertThat(approved.getResolutionEvidenceFingerprint()).isEqualTo(requested.getResolutionEvidenceFingerprint());
        assertThat(approved.getResolutionApprovalEvidenceReference()).isEqualTo("approval-evidence");
        assertThat(approved.getResolutionApprovalEvidenceVerifiedBy()).isEqualTo("approval-verifier");
        assertThat(approved.getResolutionApprovalEvidenceFingerprint()).isNotBlank();
        assertThat(approved.getResolutionRequestReason()).isEqualTo("request reason");
        assertThat(approved.getResolutionApprovalReason()).isEqualTo("approval reason");
    }

    @Test
    void shouldUseExplicitSingleControlAttestationOutsideBankMode() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        mockPersistence(fixture, record);
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class))).thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument resolved = fixture.handler.resolve("event-1", request(), "ops-1");

        assertThat(resolved.getStatus()).isEqualTo(TransactionalOutboxStatus.PUBLISHED);
        assertThat(resolved.getResolutionControlMode()).isEqualTo("SINGLE_CONTROL_OPERATOR_ATTESTED");
        assertThat(resolved.getPublicationConfirmationProvenance())
                .isEqualTo(OutboxPublicationConfirmationProvenance.MANUAL_SINGLE_CONTROL_ATTESTED);
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
    void shouldFenceSingleControlResolutionByIdentityStatusPendingStateAndFreshness() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        mockPersistence(fixture, record);
        when(fixture.mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        fixture.handler.resolve("event-1", request(), "ops-1");

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(fixture.mongoTemplate).findAndModify(
                queryCaptor.capture(),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(TransactionalOutboxRecordDocument.class)
        );
        String query = queryCaptor.getValue().getQueryObject().toString();
        assertThat(query)
                .contains("event-1")
                .contains("PUBLISH_CONFIRMATION_UNKNOWN")
                .contains("resolution_pending")
                .contains("updated_at");
        verify(fixture.repository, never()).save(any());
    }

    @Test
    void shouldFailClosedWhenConditionalTransitionLosesTheRace() {
        Fixture fixture = fixture(false, false);
        TransactionalOutboxRecordDocument record = record();
        when(fixture.repository.findById("event-1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> fixture.handler.resolve("event-1", request(), "ops-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed concurrently");

        verify(fixture.repository, never()).save(any());
        verify(fixture.mongoTemplate, never()).updateFirst(
                any(Query.class),
                any(Update.class),
                eq(AlertDocument.class)
        );
    }

    @Test
    void shouldProjectDualControlRequestFromPersistedOutboxRecord() {
        Fixture fixture = fixture(true, true);
        TransactionalOutboxRecordDocument record = record();
        mockPersistence(fixture, record, saved -> {
            saved.setAttempts(5);
            saved.setResolutionRequestedAt(REQUESTED_AT);
            saved.setResolutionRequestedBy("persisted-requester");
            saved.setResolutionRequestReason("persisted pending reason");
            saved.setResolutionEvidenceType("PERSISTED_EVIDENCE");
            saved.setResolutionEvidenceReference("persisted-pending-reference");
            saved.setResolutionEvidenceVerifiedAt(APPROVED_AT);
            saved.setResolutionEvidenceVerifiedBy("persisted-verifier");
        });
        when(fixture.mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(AlertDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        TransactionalOutboxRecordDocument saved = fixture.handler.resolve("event-1", request(), "ops-1");

        Update projected = projectedAlertUpdate(fixture);
        Document set = projected.getUpdateObject().get("$set", Document.class);
        assertThat(set.getString("decisionOutboxStatus")).isEqualTo(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThat(set.getInteger("decisionOutboxAttempts")).isEqualTo(saved.getAttempts());
        assertThat(set.get("decisionOutboxResolutionPending")).isEqualTo(true);
        assertThat(set.get("decisionOutboxResolutionRequestedAt")).isEqualTo(saved.getResolutionRequestedAt());
        assertThat(set.getString("decisionOutboxResolutionRequestedBy")).isEqualTo(saved.getResolutionRequestedBy());
        assertThat(set.getString("decisionOutboxResolutionRequestReason"))
                .isEqualTo(saved.getResolutionRequestReason());
        assertThat(projected.getUpdateObject().get("$unset", Document.class))
                .containsKey("decisionOutboxResolutionApprovalReason");
        assertResolutionEvidence(set, saved);
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
        mockPersistence(fixture, record);
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
        mockPersistence(fixture, record);
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
        mockPersistence(fixture, record, saved -> {
            saved.setAttempts(7);
            saved.setResolutionApprovedAt(APPROVED_AT);
            saved.setResolutionApprovedBy("persisted-approver");
            saved.setResolutionApprovalReason("persisted resolution reason");
            saved.setResolutionEvidenceType("PERSISTED_EVIDENCE");
            saved.setResolutionEvidenceReference("persisted-resolution-reference");
            saved.setResolutionEvidenceVerifiedAt(REQUESTED_AT);
            saved.setResolutionEvidenceVerifiedBy("persisted-verifier");
            if (saved.getStatus() == TransactionalOutboxStatus.PUBLISHED) {
                saved.setPublishedAt(PUBLISHED_AT);
            }
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
        assertThat(set.getString("decisionOutboxResolutionApprovalReason"))
                .isEqualTo(saved.getResolutionApprovalReason());
        assertResolutionEvidence(set, saved);
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

    private void mockPersistence(Fixture fixture, TransactionalOutboxRecordDocument initial) {
        mockPersistence(fixture, initial, ignored -> { });
    }

    private void mockPersistence(
            Fixture fixture,
            TransactionalOutboxRecordDocument initial,
            Consumer<TransactionalOutboxRecordDocument> persistedCustomizer
    ) {
        AtomicReference<TransactionalOutboxRecordDocument> state = new AtomicReference<>(persistedCopy(initial));
        when(fixture.repository.findById("event-1")).thenAnswer(invocation -> Optional.of(persistedCopy(state.get())));
        when(fixture.mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(TransactionalOutboxRecordDocument.class)
        )).thenAnswer(invocation -> {
            TransactionalOutboxRecordDocument saved = persistedCopy(state.get());
            applyUpdate(saved, invocation.getArgument(1));
            persistedCustomizer.accept(saved);
            state.set(persistedCopy(saved));
            return saved;
        });
    }

    private void applyUpdate(TransactionalOutboxRecordDocument target, Update update) {
        Document updateObject = update.getUpdateObject();
        Document set = updateObject.get("$set", Document.class);
        Document unset = updateObject.get("$unset", Document.class);
        if (set != null) {
            if (set.containsKey("status")) target.setStatus((TransactionalOutboxStatus) set.get("status"));
            if (set.containsKey("resolution_pending")) target.setResolutionPending(set.getBoolean("resolution_pending"));
            if (set.containsKey("resolution_control_mode")) target.setResolutionControlMode(set.getString("resolution_control_mode"));
            if (set.containsKey("resolution_request_id")) target.setResolutionRequestId(set.getString("resolution_request_id"));
            if (set.containsKey("resolution_proposed_outcome")) target.setResolutionProposedOutcome(set.getString("resolution_proposed_outcome"));
            if (set.containsKey("resolution_requested_by")) target.setResolutionRequestedBy(set.getString("resolution_requested_by"));
            if (set.containsKey("resolution_requested_at")) target.setResolutionRequestedAt((Instant) set.get("resolution_requested_at"));
            if (set.containsKey("resolution_request_reason")) target.setResolutionRequestReason(set.getString("resolution_request_reason"));
            if (set.containsKey("resolution_approval_reason")) target.setResolutionApprovalReason(set.getString("resolution_approval_reason"));
            if (set.containsKey("resolution_evidence_type")) target.setResolutionEvidenceType(set.getString("resolution_evidence_type"));
            if (set.containsKey("resolution_evidence_reference")) target.setResolutionEvidenceReference(set.getString("resolution_evidence_reference"));
            if (set.containsKey("resolution_evidence_verified_at")) target.setResolutionEvidenceVerifiedAt((Instant) set.get("resolution_evidence_verified_at"));
            if (set.containsKey("resolution_evidence_verified_by")) target.setResolutionEvidenceVerifiedBy(set.getString("resolution_evidence_verified_by"));
            if (set.containsKey("resolution_evidence_fingerprint")) target.setResolutionEvidenceFingerprint(set.getString("resolution_evidence_fingerprint"));
            if (set.containsKey("resolution_approval_evidence_type")) target.setResolutionApprovalEvidenceType(set.getString("resolution_approval_evidence_type"));
            if (set.containsKey("resolution_approval_evidence_reference")) target.setResolutionApprovalEvidenceReference(set.getString("resolution_approval_evidence_reference"));
            if (set.containsKey("resolution_approval_evidence_verified_at")) target.setResolutionApprovalEvidenceVerifiedAt((Instant) set.get("resolution_approval_evidence_verified_at"));
            if (set.containsKey("resolution_approval_evidence_verified_by")) target.setResolutionApprovalEvidenceVerifiedBy(set.getString("resolution_approval_evidence_verified_by"));
            if (set.containsKey("resolution_approval_evidence_fingerprint")) target.setResolutionApprovalEvidenceFingerprint(set.getString("resolution_approval_evidence_fingerprint"));
            if (set.containsKey("resolution_approved_by")) target.setResolutionApprovedBy(set.getString("resolution_approved_by"));
            if (set.containsKey("resolution_approved_at")) target.setResolutionApprovedAt((Instant) set.get("resolution_approved_at"));
            if (set.containsKey("last_error")) target.setLastError(set.getString("last_error"));
            if (set.containsKey("published_at")) target.setPublishedAt((Instant) set.get("published_at"));
            if (set.containsKey("publication_confirmation_provenance")) target.setPublicationConfirmationProvenance(
                    (OutboxPublicationConfirmationProvenance) set.get("publication_confirmation_provenance")
            );
            if (set.containsKey("updated_at")) target.setUpdatedAt((Instant) set.get("updated_at"));
        }
        if (unset != null) {
            if (unset.containsKey("lease_owner")) target.setLeaseOwner(null);
            if (unset.containsKey("lease_expires_at")) target.setLeaseExpiresAt(null);
            if (unset.containsKey("last_error")) target.setLastError(null);
            if (unset.containsKey("published_at")) target.setPublishedAt(null);
            if (unset.containsKey("publication_confirmation_provenance")) target.setPublicationConfirmationProvenance(null);
            if (unset.containsKey("resolution_approval_reason")) target.setResolutionApprovalReason(null);
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
        saved.setPublicationConfirmationProvenance(source.getPublicationConfirmationProvenance());
        saved.setConfirmationUnknownAt(source.getConfirmationUnknownAt());
        saved.setResolutionPending(source.isResolutionPending());
        saved.setResolutionControlMode(source.getResolutionControlMode());
        saved.setResolutionRequestId(source.getResolutionRequestId());
        saved.setResolutionProposedOutcome(source.getResolutionProposedOutcome());
        saved.setResolutionRequestedAt(source.getResolutionRequestedAt());
        saved.setResolutionRequestedBy(source.getResolutionRequestedBy());
        saved.setResolutionRequestReason(source.getResolutionRequestReason());
        saved.setResolutionApprovalReason(source.getResolutionApprovalReason());
        saved.setResolutionEvidenceType(source.getResolutionEvidenceType());
        saved.setResolutionEvidenceReference(source.getResolutionEvidenceReference());
        saved.setResolutionEvidenceVerifiedAt(source.getResolutionEvidenceVerifiedAt());
        saved.setResolutionEvidenceVerifiedBy(source.getResolutionEvidenceVerifiedBy());
        saved.setResolutionEvidenceFingerprint(source.getResolutionEvidenceFingerprint());
        saved.setResolutionApprovalEvidenceType(source.getResolutionApprovalEvidenceType());
        saved.setResolutionApprovalEvidenceReference(source.getResolutionApprovalEvidenceReference());
        saved.setResolutionApprovalEvidenceVerifiedAt(source.getResolutionApprovalEvidenceVerifiedAt());
        saved.setResolutionApprovalEvidenceVerifiedBy(source.getResolutionApprovalEvidenceVerifiedBy());
        saved.setResolutionApprovalEvidenceFingerprint(source.getResolutionApprovalEvidenceFingerprint());
        saved.setResolutionApprovedAt(source.getResolutionApprovedAt());
        saved.setResolutionApprovedBy(source.getResolutionApprovedBy());
        saved.setUpdatedAt(source.getUpdatedAt());
        return saved;
    }

    private void assertResolutionEvidence(Document set, TransactionalOutboxRecordDocument saved) {
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
        return request(resolution, null);
    }

    private OutboxConfirmationResolutionRequest request(
            OutboxConfirmationResolution resolution,
            String pendingRequestId
    ) {
        return request(resolution, pendingRequestId, "broker offset verified", evidence(
                "topic=fraud-decisions,partition=0,offset=42",
                "ops"
        ));
    }

    private OutboxConfirmationResolutionRequest request(
            OutboxConfirmationResolution resolution,
            String pendingRequestId,
            String reason,
            ResolutionEvidenceReference evidence
    ) {
        return new OutboxConfirmationResolutionRequest(
                resolution,
                pendingRequestId,
                reason,
                evidence
        );
    }

    private ResolutionEvidenceReference evidence(String reference, String verifiedBy) {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                reference,
                Instant.parse("2026-05-02T10:00:00Z"),
                verifiedBy
        );
    }

    private void assertChangedProposalRejected(
            OutboxConfirmationResolution proposed,
            OutboxConfirmationResolution attemptedApproval
    ) {
        Fixture fixture = fixture(true, true);
        mockPersistence(fixture, record());
        when(fixture.mongoTemplate.updateFirst(any(), any(), any(Class.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        TransactionalOutboxRecordDocument requested = fixture.handler.resolve(
                "event-1",
                request(proposed),
                "requester"
        );

        assertThatThrownBy(() -> fixture.handler.resolve(
                "event-1",
                request(attemptedApproval, requested.getResolutionRequestId()),
                "approver"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot change the proposed resolution");
    }

    private record Fixture(
            TransactionalOutboxRecordRepository repository,
            MongoTemplate mongoTemplate,
            OutboxConfirmationResolutionMutationHandler handler
    ) {
    }
}
