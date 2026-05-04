package com.frauddetection.alert.regulated;

import java.time.Instant;

public record RegulatedMutationClaimToken(
        String commandId,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant claimedAt,
        int attemptCount,
        RegulatedMutationModelVersion mutationModelVersion,
        RegulatedMutationState expectedInitialState,
        RegulatedMutationExecutionStatus expectedExecutionStatus
) {
    public RegulatedMutationClaimToken {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("Regulated mutation claim token requires commandId.");
        }
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("Regulated mutation claim token requires leaseOwner.");
        }
        if (leaseExpiresAt == null) {
            throw new IllegalArgumentException("Regulated mutation claim token requires leaseExpiresAt.");
        }
        if (claimedAt == null) {
            throw new IllegalArgumentException("Regulated mutation claim token requires claimedAt.");
        }
        if (mutationModelVersion == null) {
            throw new IllegalArgumentException("Regulated mutation claim token requires mutationModelVersion.");
        }
        if (expectedInitialState == null) {
            throw new IllegalArgumentException("Regulated mutation claim token requires expectedInitialState.");
        }
        if (expectedExecutionStatus == null) {
            throw new IllegalArgumentException("Regulated mutation claim token requires expectedExecutionStatus.");
        }
    }
}
