package com.frauddetection.alert.outbox;

import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        assertThat(set.getString("decisionOutboxResolutionRequestId")).isEqualTo("request-1");
        assertThat(set.getString("decisionOutboxResolutionProposedOutcome")).isEqualTo("PUBLISHED");
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
        assertThat(set.getString("decisionOutboxResolutionEvidenceFingerprint"))
                .isEqualTo("request-evidence-fingerprint");
        assertThat(set.getString("decisionOutboxResolutionApprovalEvidenceType")).isEqualTo("BROKER_OFFSET");
        assertThat(set.getString("decisionOutboxResolutionApprovalEvidenceReference"))
                .isEqualTo("partition=1,offset=43");
        assertThat(set.get("decisionOutboxResolutionApprovalEvidenceVerifiedAt"))
                .isEqualTo(record.getResolutionApprovalEvidenceVerifiedAt());
        assertThat(set.getString("decisionOutboxResolutionApprovalEvidenceVerifiedBy"))
                .isEqualTo("approval-verifier");
        assertThat(set.getString("decisionOutboxResolutionApprovalEvidenceFingerprint"))
                .isEqualTo("approval-evidence-fingerprint");
        assertThat(set.get("decisionOutboxResolutionApprovedAt")).isEqualTo(record.getResolutionApprovedAt());
        assertThat(set.getString("decisionOutboxResolutionApprovedBy")).isEqualTo("approver");
        if (sourceStatus == TransactionalOutboxStatus.PUBLISHED) {
            assertThat(set.get("decisionOutboxPublishedAt")).isEqualTo(record.getPublishedAt());
            assertThat(set.getString("decisionOutboxPublicationConfirmationProvenance"))
                    .isEqualTo("MANUAL_DUAL_CONTROL_ATTESTED");
        } else {
            assertThat(set).doesNotContainKey("decisionOutboxPublishedAt");
            assertThat(unset).containsKeys(
                    "decisionOutboxPublishedAt",
                    "decisionOutboxPublicationConfirmationProvenance"
            );
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
    void projectionTargetIsFencedByEventIdentityAndMonotonicRevision() {
        TransactionalOutboxRecordDocument record = record(TransactionalOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        record.setResolutionPending(true);
        record.setProjectionRevision(7L);
        record.setResolutionApprovedAt(null);
        record.setResolutionApprovedBy(null);
        record.setResolutionApprovalReason(null);

        String target = OutboxAlertProjectionPolicy.manualResolution(record)
                .target("alert-1")
                .getQueryObject()
                .toString();

        assertThat(target)
                .contains("decisionOutboxEventId=event-1")
                .contains("decisionOutboxProjectionRevision=Document{{$lte=7}}")
                .doesNotContain("decisionOutboxResolutionApprovedAt")
                .doesNotContain("decisionOutboxAttempts")
                .doesNotContain("decisionOutboxStatus");
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
        record.setEventId("event-1");
        record.setStatus(status);
        record.setAttempts(4);
        record.setLastError(status == TransactionalOutboxStatus.PUBLISHED ? null : "source-error");
        record.setPublishedAt(Instant.parse("2026-09-30T10:30:00Z"));
        record.setPublicationConfirmationProvenance(
                OutboxPublicationConfirmationProvenance.MANUAL_DUAL_CONTROL_ATTESTED
        );
        record.setResolutionPending(false);
        record.setResolutionRequestId("request-1");
        record.setResolutionProposedOutcome("PUBLISHED");
        record.setResolutionRequestedAt(Instant.parse("2026-09-30T10:15:00Z"));
        record.setResolutionRequestedBy("requester");
        record.setResolutionRequestReason("request reason");
        record.setResolutionApprovalReason("approval reason");
        record.setResolutionEvidenceType("BROKER_OFFSET");
        record.setResolutionEvidenceReference("partition=1,offset=42");
        record.setResolutionEvidenceVerifiedAt(Instant.parse("2026-09-30T10:20:00Z"));
        record.setResolutionEvidenceVerifiedBy("verifier");
        record.setResolutionEvidenceFingerprint("request-evidence-fingerprint");
        record.setResolutionApprovalEvidenceType("BROKER_OFFSET");
        record.setResolutionApprovalEvidenceReference("partition=1,offset=43");
        record.setResolutionApprovalEvidenceVerifiedAt(Instant.parse("2026-09-30T10:21:00Z"));
        record.setResolutionApprovalEvidenceVerifiedBy("approval-verifier");
        record.setResolutionApprovalEvidenceFingerprint("approval-evidence-fingerprint");
        record.setResolutionApprovedAt(Instant.parse("2026-09-30T10:25:00Z"));
        record.setResolutionApprovedBy("approver");
        return record;
    }
}
