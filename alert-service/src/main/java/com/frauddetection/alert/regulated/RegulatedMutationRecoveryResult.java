package com.frauddetection.alert.regulated;

public record RegulatedMutationRecoveryResult(
        String idempotencyKey,
        RegulatedMutationState state,
        RegulatedMutationExecutionStatus executionStatus,
        RegulatedMutationRecoveryOutcome outcome
) {
}
