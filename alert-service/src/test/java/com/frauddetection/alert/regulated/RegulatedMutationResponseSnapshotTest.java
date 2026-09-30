package com.frauddetection.alert.regulated;

import com.frauddetection.alert.outbox.OutboxRecordResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatedMutationResponseSnapshotTest {

    @Test
    void outboxResponseRoundTripPreservesIndependentSemanticValues() {
        OutboxRecordResponse original = new OutboxRecordResponse(
                "event-17",
                "dedupe-17",
                "command-17",
                "DECISION_OUTBOX",
                "alert-17",
                "FRAUD_DECISION",
                "payload-hash-17",
                "PUBLISHED",
                3,
                "last-error",
                Instant.parse("2026-09-30T10:30:00Z"),
                Instant.parse("2026-09-30T10:00:00Z"),
                Instant.parse("2026-09-30T10:45:00Z"),
                true,
                "DUAL_CONTROL_REQUESTED",
                "requester-17",
                Instant.parse("2026-09-30T10:15:00Z"),
                "approver-17",
                Instant.parse("2026-09-30T10:25:00Z"),
                "FINALIZED_EVIDENCE_PENDING_EXTERNAL"
        );

        OutboxRecordResponse restored = RegulatedMutationResponseSnapshot.from(original)
                .toOutboxRecordResponse();

        assertThat(restored).isEqualTo(original);
        assertThat(restored.confirmationUnknownAt()).isNotEqualTo(restored.resolutionRequestedAt());
    }
}
