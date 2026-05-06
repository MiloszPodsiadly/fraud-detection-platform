package com.frauddetection.alert.regulated.chaos;

public enum Fdp38LiveRuntimeCheckpoint {
    BEFORE_LEGACY_BUSINESS_MUTATION,
    AFTER_ATTEMPTED_AUDIT_BEFORE_BUSINESS_MUTATION,
    BEFORE_FDP29_LOCAL_FINALIZE,
    BEFORE_SUCCESS_AUDIT_RETRY
}
