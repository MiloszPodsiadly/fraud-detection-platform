package com.frauddetection.alert.audit;

public enum AuditTrustLevel {
    INTERNAL_ONLY,
    PARTIAL_EXTERNAL,
    EXTERNALLY_ANCHORED,
    SIGNED_ATTESTATION,
    UNAVAILABLE
}
