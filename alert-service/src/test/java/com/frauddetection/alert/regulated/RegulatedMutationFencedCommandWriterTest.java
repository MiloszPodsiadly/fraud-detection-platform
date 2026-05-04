package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationFencedCommandWriterTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RegulatedMutationFencedCommandWriter writer = new RegulatedMutationFencedCommandWriter(
            mongoTemplate,
            new AlertServiceMetrics(meterRegistry)
    );

    @Test
    void successfulTransitionUsesLeaseOwnerExpiryStateAndExecutionStatusFence() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set("attempted_audit_recorded", true)
        );

        Query query = capturedQuery();
        String queryJson = query.getQueryObject().toString();
        assertThat(queryJson).contains("_id=command-1");
        assertThat(queryJson).contains("lease_owner=owner-a");
        assertThat(queryJson).contains("lease_expires_at");
        assertThat(queryJson).contains("state=REQUESTED");
        assertThat(queryJson).contains("execution_status=PROCESSING");
        Document set = setDocument();
        assertThat(set.get("state")).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(set.get("execution_status")).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(set.get("attempted_audit_recorded")).isEqualTo(true);
    }

    @Test
    void activeLeaseValidationUsesSameLeaseOwnerExpiryStateAndExecutionStatusFence() {
        when(mongoTemplate.count(any(Query.class), eq(RegulatedMutationCommandDocument.class))).thenReturn(1L);

        writer.validateActiveLease(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).count(captor.capture(), eq(RegulatedMutationCommandDocument.class));
        String queryJson = captor.getValue().getQueryObject().toString();
        assertThat(queryJson).contains("_id=command-1");
        assertThat(queryJson).contains("lease_owner=owner-a");
        assertThat(queryJson).contains("lease_expires_at");
        assertThat(queryJson).contains("state=REQUESTED");
        assertThat(queryJson).contains("execution_status=PROCESSING");
    }

    @Test
    void activeLeaseValidationRejectsExpiredLeaseBeforeBusinessMutation() {
        when(mongoTemplate.count(any(Query.class), eq(RegulatedMutationCommandDocument.class))).thenReturn(0L);
        RegulatedMutationCommandDocument current = current("owner-a", Instant.now().minusSeconds(1));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.validateActiveLease(
                token("owner-a", Instant.now().minusSeconds(1)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        ))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPIRED_LEASE);
    }

    @Test
    void recoveryTransitionUsesExpectedStateStatusAndNonClaimedRepairFence() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        RegulatedMutationCommandDocument document = current("owner-a", Instant.now().minusSeconds(1));
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        document.setState(RegulatedMutationState.FINALIZING);

        writer.recoveryTransition(
                document,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "FINALIZING_RETRY_REQUIRES_RECONCILIATION",
                update -> update.set("degradation_reason", "FINALIZING_RETRY_REQUIRES_RECONCILIATION")
        );

        String queryJson = capturedQuery().getQueryObject().toString();
        assertThat(queryJson).contains("_id=command-1");
        assertThat(queryJson).contains("state=FINALIZING");
        assertThat(queryJson).contains("execution_status=PROCESSING");
        assertThat(queryJson).contains("lease_expires_at");
        Document set = setDocument();
        assertThat(set.get("state")).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(set.get("execution_status")).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(set.get("degradation_reason")).isEqualTo("FINALIZING_RETRY_REQUIRES_RECONCILIATION");
    }

    @Test
    void recoveryTransitionIncludesPublicStatusWhenPresent() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        RegulatedMutationCommandDocument document = current("owner-a", Instant.now().minusSeconds(1));
        document.setPublicStatus(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);

        writer.recoveryTransition(
                document,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        );

        assertThat(capturedQuery().getQueryObject().toString())
                .contains("public_status=FINALIZE_RECOVERY_REQUIRED");
    }

    @Test
    void activeProcessingCommandWithUnexpiredLeaseRejectsNonClaimedRecoveryTransition() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> writer.recoveryTransition(
                current("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);

        assertThat(capturedQuery().getQueryObject().toString())
                .contains("execution_status=PROCESSING")
                .contains("lease_expires_at");
        assertThat(meterRegistry.find("regulated_mutation_recovery_write_conflict_total")
                .tag("model_version", "LEGACY_REGULATED_MUTATION")
                .tag("state", "REQUESTED")
                .tag("reason", "RECOVERY_WRITE_CONFLICT")
                .counter()).isNotNull();
    }

    @Test
    void expiredProcessingCommandCanUseNonClaimedRecoveryTransitionWhenExpectedStateStillMatches() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        writer.recoveryTransition(
                current("owner-a", Instant.now().minusSeconds(1)),
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        );

        assertThat(setDocument().get("state")).isEqualTo(RegulatedMutationState.FAILED);
    }

    @Test
    void recoveryTransitionStateMismatchFailsClosed() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument document = current("owner-a", Instant.now().minusSeconds(1));
        document.setState(RegulatedMutationState.FINALIZING);

        assertThatThrownBy(() -> writer.recoveryTransition(
                document,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);
    }

    @Test
    void recoveryTransitionPublicStatusMismatchFailsClosed() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument document = current("owner-a", Instant.now().minusSeconds(1));
        document.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);

        assertThatThrownBy(() -> writer.recoveryTransition(
                document,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);
    }

    @Test
    void recoveryTransitionMutationModelVersionMismatchFailsClosed() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument document = current("owner-a", Instant.now().minusSeconds(1));
        document.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);

        assertThatThrownBy(() -> writer.recoveryTransition(
                document,
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);
    }

    @Test
    void recoveryTransitionConflictFailsClosed() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> writer.recoveryTransition(
                current("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.FAILED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED,
                "RECOVERY_REQUIRED",
                null
        )).isInstanceOf(RegulatedMutationRecoveryWriteConflictException.class);
    }

    @Test
    void allowedFieldUpdatesCannotMutateLeaseOwner() {
        assertProtectedFieldRejected("lease_owner", "owner-b");
    }

    @Test
    void allowedFieldUpdatesCannotMutateIdempotencyKey() {
        assertProtectedFieldRejected("idempotency_key", "idem-other");
    }

    @Test
    void allowedFieldUpdatesCannotMutateRequestHash() {
        assertProtectedFieldRejected("request_hash", "request-other");
    }

    @Test
    void allowedFieldUpdatesCannotMutateMutationModelVersion() {
        assertProtectedFieldRejected("mutation_model_version", RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
    }

    @Test
    void normalAllowedTransitionEvidenceFieldsStillWork() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.EVIDENCE_PENDING,
                RegulatedMutationExecutionStatus.COMPLETED,
                null,
                update -> {
                    update.set("response_snapshot", "snapshot");
                    update.set("outbox_event_id", "outbox-1");
                    update.set("local_commit_marker", "marker-1");
                    update.set("success_audit_id", "success-1");
                    update.set("degradation_reason", "NONE");
                    update.set("public_status", SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
                }
        );

        Document set = setDocument();
        assertThat(set.get("response_snapshot")).isEqualTo("snapshot");
        assertThat(set.get("outbox_event_id")).isEqualTo("outbox-1");
        assertThat(set.get("local_commit_marker")).isEqualTo("marker-1");
        assertThat(set.get("success_audit_id")).isEqualTo("success-1");
        assertThat(set.get("degradation_reason")).isEqualTo("NONE");
        assertThat(set.get("public_status")).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
    }

    @Test
    void staleLeaseOwnerIsRejected() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument current = current("owner-b", Instant.now().plusSeconds(30));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        ))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.STALE_LEASE_OWNER);
    }

    @Test
    void expiredLeaseIsRejected() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument current = current("owner-a", Instant.now().minusSeconds(1));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.transition(
                token("owner-a", Instant.now().minusSeconds(1)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        ))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPIRED_LEASE);
    }

    @Test
    void wrongExpectedStateIsRejected() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument current = current("owner-a", Instant.now().plusSeconds(30));
        current.setState(RegulatedMutationState.BUSINESS_COMMITTING);
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        ))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPECTED_STATE_MISMATCH);
    }

    @Test
    void wrongExpectedExecutionStatusIsRejected() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument current = current("owner-a", Instant.now().plusSeconds(30));
        current.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        ))
                .isInstanceOf(StaleRegulatedMutationLeaseException.class)
                .extracting("reason")
                .isEqualTo(StaleRegulatedMutationLeaseReason.EXPECTED_STATUS_MISMATCH);
    }

    @Test
    void staleWriteRejectionUsesBoundedMetricLabels() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument current = current("owner-b", Instant.now().plusSeconds(30));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                null
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        assertThat(meterRegistry.find("regulated_mutation_stale_write_rejected_total")
                .tag("model_version", "LEGACY_REGULATED_MUTATION")
                .tag("state", "REQUESTED")
                .tag("reason", "STALE_LEASE_OWNER")
                .counter()).isNotNull();
        assertThat(meterRegistry.find("regulated_mutation_lease_remaining_at_transition_seconds")
                .tag("model_version", "LEGACY_REGULATED_MUTATION")
                .tag("state", "REQUESTED")
                .tag("outcome", "REJECTED")
                .timer()).isNotNull();
        assertThat(meterRegistry.find("regulated_mutation_transition_latency_seconds")
                .tag("model_version", "LEGACY_REGULATED_MUTATION")
                .tag("state", "REQUESTED")
                .tag("outcome", "REJECTED")
                .timer()).isNotNull();
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                        .doesNotContain("command-1")
                        .doesNotContain("owner-a")
                        .doesNotContain("owner-b"));
    }

    @Test
    void leaseBudgetWarningUsesBoundedLabelsWhenRemainingLeaseIsBelowThreshold() {
        when(mongoTemplate.count(any(Query.class), eq(RegulatedMutationCommandDocument.class))).thenReturn(1L);

        writer.validateActiveLease(
                new RegulatedMutationClaimToken(
                        "command-1",
                        "owner-a",
                        Instant.now().plusMillis(50),
                        Instant.now().minusMillis(1000),
                        1,
                        RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                        RegulatedMutationState.REQUESTED,
                        RegulatedMutationExecutionStatus.PROCESSING
                ),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );

        assertThat(meterRegistry.find("regulated_mutation_lease_budget_warning_total")
                .tag("model_version", "LEGACY_REGULATED_MUTATION")
                .tag("state", "REQUESTED")
                .tag("threshold", "LOW_REMAINING")
                .counter()).isNotNull();
        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                        .doesNotContain("command-1")
                        .doesNotContain("owner-a"));
    }

    @Test
    void metricLabelsDoNotContainBusinessIdentifiersOrRawErrors() {
        when(mongoTemplate.updateFirst(any(), any(), eq(RegulatedMutationCommandDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        RegulatedMutationCommandDocument current = current("owner-sensitive", Instant.now().plusSeconds(30));
        when(mongoTemplate.findById("command-1", RegulatedMutationCommandDocument.class)).thenReturn(current);

        assertThatThrownBy(() -> writer.transition(
                new RegulatedMutationClaimToken(
                        "command-1",
                        "owner-sensitive",
                        Instant.now().plusSeconds(30),
                        Instant.now(),
                        1,
                        RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                        RegulatedMutationState.REQUESTED,
                        RegulatedMutationExecutionStatus.PROCESSING
                ),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                "raw exception path /api/v1/alerts/alert-123 actor-456 idem-789",
                null
        )).isInstanceOf(StaleRegulatedMutationLeaseException.class);

        assertThat(meterRegistry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                        .doesNotContain("command-1")
                        .doesNotContain("alert-123")
                        .doesNotContain("actor-456")
                        .doesNotContain("owner-sensitive")
                        .doesNotContain("idem-789")
                        .doesNotContain("request-hash")
                        .doesNotContain("/api/v1")
                        .doesNotContain("raw exception"));
    }

    private void assertProtectedFieldRejected(String field, Object value) {
        assertThatThrownBy(() -> writer.transition(
                token("owner-a", Instant.now().plusSeconds(30)),
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                null,
                update -> update.set(field, value)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protected field: " + field);
    }

    private Query capturedQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(captor.capture(), any(Update.class), eq(RegulatedMutationCommandDocument.class));
        return captor.getValue();
    }

    private Document setDocument() {
        ArgumentCaptor<Update> captor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), captor.capture(), eq(RegulatedMutationCommandDocument.class));
        return (Document) captor.getValue().getUpdateObject().get("$set");
    }

    private RegulatedMutationClaimToken token(String owner, Instant expiresAt) {
        return new RegulatedMutationClaimToken(
                "command-1",
                owner,
                expiresAt,
                Instant.now(),
                1,
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
    }

    private RegulatedMutationCommandDocument current(String owner, Instant expiresAt) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setLeaseOwner(owner);
        document.setLeaseExpiresAt(expiresAt);
        document.setState(RegulatedMutationState.REQUESTED);
        document.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        return document;
    }
}
