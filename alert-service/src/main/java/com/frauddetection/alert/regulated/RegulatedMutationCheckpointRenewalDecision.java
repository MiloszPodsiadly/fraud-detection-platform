package com.frauddetection.alert.regulated;

import java.time.Instant;

public record RegulatedMutationCheckpointRenewalDecision(
        RegulatedMutationCheckpointRenewalDecisionType type,
        RegulatedMutationRenewalCheckpoint checkpoint,
        RegulatedMutationLeaseRenewalReason reason,
        Instant newLeaseExpiresAt
) {

    public static RegulatedMutationCheckpointRenewalDecision renewed(
            RegulatedMutationRenewalCheckpoint checkpoint,
            Instant newLeaseExpiresAt
    ) {
        return new RegulatedMutationCheckpointRenewalDecision(
                RegulatedMutationCheckpointRenewalDecisionType.RENEWED,
                checkpoint,
                RegulatedMutationLeaseRenewalReason.NONE,
                newLeaseExpiresAt
        );
    }

    public static RegulatedMutationCheckpointRenewalDecision skipped(RegulatedMutationRenewalCheckpoint checkpoint) {
        return new RegulatedMutationCheckpointRenewalDecision(
                RegulatedMutationCheckpointRenewalDecisionType.SKIPPED_NOT_REQUIRED,
                checkpoint,
                RegulatedMutationLeaseRenewalReason.NONE,
                null
        );
    }
}
