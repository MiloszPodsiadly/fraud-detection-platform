package com.frauddetection.alert.regulated;

public enum RegulatedMutationReplayDecisionType {
    NONE,
    ACTIVE_IN_PROGRESS,
    REPLAY_SNAPSHOT,
    RECOVERY_REQUIRED_RESPONSE,
    REJECTED_RESPONSE,
    FINALIZING_REQUIRES_RECOVERY
}
