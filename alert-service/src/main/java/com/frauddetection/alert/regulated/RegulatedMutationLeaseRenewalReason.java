package com.frauddetection.alert.regulated;

public enum RegulatedMutationLeaseRenewalReason {
    NONE,
    STALE_OWNER,
    EXPIRED_LEASE,
    NON_RENEWABLE_STATE,
    TERMINAL_STATE,
    RECOVERY_STATE,
    MODEL_VERSION_MISMATCH,
    EXECUTION_STATUS_MISMATCH,
    BUDGET_EXCEEDED,
    UNKNOWN
}
