package com.frauddetection.alert.regulated;

public enum RegulatedMutationCheckpointRenewalDecisionType {
    RENEWED,
    SKIPPED_NOT_REQUIRED,
    REJECTED_STALE,
    REJECTED_EXPIRED,
    REJECTED_BUDGET_EXCEEDED,
    REJECTED_NON_RENEWABLE_STATE,
    REJECTED_TERMINAL_OR_RECOVERY
}
