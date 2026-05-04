package com.frauddetection.alert.regulated;

public enum StaleRegulatedMutationLeaseReason {
    STALE_LEASE_OWNER,
    EXPIRED_LEASE,
    EXPECTED_STATE_MISMATCH,
    EXPECTED_STATUS_MISMATCH,
    COMMAND_NOT_FOUND,
    UNKNOWN
}
