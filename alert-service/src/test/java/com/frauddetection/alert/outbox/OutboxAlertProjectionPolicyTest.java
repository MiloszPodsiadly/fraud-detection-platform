package com.frauddetection.alert.outbox;

import com.frauddetection.alert.service.DecisionOutboxStatus;
import org.bson.Document;
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
        assertThat(set.get("decisionOutboxResolutionPending")).isEqualTo(true);
        assertThat(set.getString("decisionOutboxResolutionRequestedBy")).isEqualTo("requester");
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

    private TransactionalOutboxRecordDocument record(TransactionalOutboxStatus status) {
        TransactionalOutboxRecordDocument record = new TransactionalOutboxRecordDocument();
        record.setStatus(status);
        record.setAttempts(4);
        record.setLastError(status == TransactionalOutboxStatus.PUBLISHED ? null : "source-error");
        record.setPublishedAt(Instant.parse("2026-09-30T10:30:00Z"));
        record.setResolutionPending(true);
        record.setResolutionRequestedAt(Instant.parse("2026-09-30T10:15:00Z"));
        record.setResolutionRequestedBy("requester");
        record.setResolutionReason("reviewed");
        record.setResolutionEvidenceType("BROKER_OFFSET");
        record.setResolutionEvidenceReference("partition=1,offset=42");
        record.setResolutionEvidenceVerifiedAt(Instant.parse("2026-09-30T10:20:00Z"));
        record.setResolutionEvidenceVerifiedBy("verifier");
        record.setResolutionApprovedAt(Instant.parse("2026-09-30T10:25:00Z"));
        record.setResolutionApprovedBy("approver");
        return record;
    }
}
