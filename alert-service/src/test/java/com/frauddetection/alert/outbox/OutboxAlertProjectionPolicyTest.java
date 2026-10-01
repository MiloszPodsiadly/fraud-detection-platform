package com.frauddetection.alert.outbox;

import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxAlertProjectionPolicyTest {

    @ParameterizedTest
    @MethodSource("projectionStates")
    void recoveryBuildsAuthoritativeProjectionForEveryProducingState(
            TransactionalOutboxStatus sourceStatus,
            String expectedProjectionStatus,
            String expectedReason
    ) {
        TransactionalOutboxRecordDocument record = record(sourceStatus);

        Document update = OutboxAlertProjectionPolicy.recovery(record).update().getUpdateObject();
        Document set = (Document) update.get("$set");
        Document unset = (Document) update.get("$unset");

        assertThat(set.getString("decisionOutboxStatus")).isEqualTo(expectedProjectionStatus);
        assertThat(set.getInteger("decisionOutboxAttempts")).isEqualTo(4);
        assertThat(unset).containsKeys("decisionOutboxLeaseOwner", "decisionOutboxLeaseExpiresAt");
        assertThat(unset).containsKey("decisionOutboxResolutionPending");
        assertThat(set.get("decisionOutboxResolutionRequestedAt")).isEqualTo(record.getResolutionRequestedAt());
        assertThat(set.getString("decisionOutboxResolutionRequestedBy")).isEqualTo("requester");
        assertThat(set.getString("decisionOutboxResolutionRequestReason"))
                .isEqualTo(record.getResolutionRequestReason());
        assertThat(set.getString("decisionOutboxResolutionApprovalReason"))
                .isEqualTo(record.getResolutionApprovalReason());
        assertThat(set.getString("decisionOutboxResolutionEvidenceType")).isEqualTo(record.getResolutionEvidenceType());
        assertThat(set.getString("decisionOutboxResolutionEvidenceReference"))
                .isEqualTo(record.getResolutionEvidenceReference());
        assertThat(set.get("decisionOutboxResolutionEvidenceVerifiedAt"))
                .isEqualTo(record.getResolutionEvidenceVerifiedAt());
        assertThat(set.getString("decisionOutboxResolutionEvidenceVerifiedBy"))
                .isEqualTo(record.getResolutionEvidenceVerifiedBy());
        assertThat(set.get("decisionOutboxResolutionApprovedAt")).isEqualTo(record.getResolutionApprovedAt());
        assertThat(set.getString("decisionOutboxResolutionApprovedBy")).isEqualTo("approver");
        if (sourceStatus == TransactionalOutboxStatus.PUBLISHED) {
            assertThat(set.get("decisionOutboxPublishedAt")).isEqualTo(record.getPublishedAt());
        } else {
            assertThat(set).doesNotContainKey("decisionOutboxPublishedAt");
            assertThat(unset).containsKey("decisionOutboxPublishedAt");
        }
        if (expectedReason == null) {
            assertThat(unset).containsKeys("decisionOutboxLastError", "decisionOutboxFailureReason");
        } else {
            assertThat(set.getString("decisionOutboxLastError")).isEqualTo(expectedReason);
            assertThat(set.getString("decisionOutboxFailureReason")).isEqualTo(expectedReason);
        }
    }

    @ParameterizedTest
    @MethodSource("manualResolutionStates")
    void manualResolutionUsesTheSameAuthoritativeProjection(
            TransactionalOutboxStatus sourceStatus,
            boolean resolutionPending
    ) {
        TransactionalOutboxRecordDocument record = record(sourceStatus);
        record.setResolutionPending(resolutionPending);
        if (resolutionPending) {
            record.setResolutionApprovedAt(null);
            record.setResolutionApprovedBy(null);
            record.setResolutionApprovalReason(null);
        }

        Document manual = OutboxAlertProjectionPolicy.manualResolution(record).update().getUpdateObject();

        assertThat(manual).isEqualTo(OutboxAlertProjectionPolicy.recovery(record).update().getUpdateObject());
    }

    @Test
    void pendingProjectionHasRequestReasonWithoutFabricatingApprovalReason() {
        TransactionalOutboxRecordDocument record = record(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        record.setResolutionPending(true);
        record.setResolutionApprovedAt(null);
        record.setResolutionApprovedBy(null);
        record.setResolutionApprovalReason(null);

        Document update = OutboxAlertProjectionPolicy.manualResolution(record).update().getUpdateObject();
        Document set = update.get("$set", Document.class);
        Document unset = update.get("$unset", Document.class);

        assertThat(set.getString("decisionOutboxResolutionRequestReason")).isEqualTo("request reason");
        assertThat(unset).containsKeys(
                "decisionOutboxResolutionApprovedAt",
                "decisionOutboxResolutionApprovedBy",
                "decisionOutboxResolutionApprovalReason"
        );
    }

    @Test
    void pendingProjectionCannotTargetAnAlreadyApprovedProjection() {
        TransactionalOutboxRecordDocument record = record(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        record.setResolutionPending(true);
        record.setResolutionApprovedAt(null);
        record.setResolutionApprovedBy(null);
        record.setResolutionApprovalReason(null);

        String target = OutboxAlertProjectionPolicy.manualResolution(record)
                .target("alert-1")
                .getQueryObject()
                .toString();

        assertThat(target)
                .contains("decisionOutboxResolutionApprovedAt")
                .contains("$exists=false")
                .contains("decisionOutboxResolutionRequestedAt");
    }

    @Test
    void existingPersistedReasonMapsAccordingToTheStoredResolutionPhase() {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        ReflectionTestUtils.setField(record, "persistedResolutionReason", "stored reason");

        record.setResolutionPending(true);
        assertThat(record.getResolutionRequestReason()).isEqualTo("stored reason");
        assertThat(record.getResolutionApprovalReason()).isNull();

        record.setResolutionPending(false);
        assertThat(record.getResolutionRequestReason()).isNull();
        assertThat(record.getResolutionApprovalReason()).isEqualTo("stored reason");
    }

    private static Stream<Arguments> projectionStates() {
        return Stream.of(
                Arguments.of(TransactionalOutboxStatus.PUBLISHED, DecisionOutboxStatus.PUBLISHED, null),
                Arguments.of(
                        TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
                        DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN,
                        "source-error"
                ),
                Arguments.of(
                        TransactionalOutboxStatus.FAILED_RETRYABLE,
                        DecisionOutboxStatus.FAILED_RETRYABLE,
                        "source-error"
                ),
                Arguments.of(
                        TransactionalOutboxStatus.FAILED_TERMINAL,
                        DecisionOutboxStatus.FAILED_TERMINAL,
                        "source-error"
                ),
                Arguments.of(
                        TransactionalOutboxStatus.RECOVERY_REQUIRED,
                        DecisionOutboxStatus.FAILED_TERMINAL,
                        "MANUAL_RECOVERY_REQUIRED"
                )
        );
    }

    private static Stream<Arguments> manualResolutionStates() {
        return Stream.of(
                Arguments.of(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN, true),
                Arguments.of(TransactionalOutboxStatus.PUBLISHED, false),
                Arguments.of(TransactionalOutboxStatus.RECOVERY_REQUIRED, false)
        );
    }

    private TransactionalOutboxRecordDocument record(TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setStatus(status);
        record.setAttempts(4);
        record.setLastError(status == TransactionalOutboxStatus.PUBLISHED ? null : "source-error");
        record.setPublishedAt(Instant.parse("2026-09-30T10:30:00Z"));
        record.setResolutionPending(false);
        record.setResolutionRequestedAt(Instant.parse("2026-09-30T10:15:00Z"));
        record.setResolutionRequestedBy("requester");
        record.setResolutionRequestReason("request reason");
        record.setResolutionApprovalReason("approval reason");
        record.setResolutionEvidenceType("BROKER_OFFSET");
        record.setResolutionEvidenceReference("partition=1,offset=42");
        record.setResolutionEvidenceVerifiedAt(Instant.parse("2026-09-30T10:20:00Z"));
        record.setResolutionEvidenceVerifiedBy("verifier");
        record.setResolutionApprovedAt(Instant.parse("2026-09-30T10:25:00Z"));
        record.setResolutionApprovedBy("approver");
        return record;
    }
}
