package com.frauddetection.enricher.graph;

/** Bounded, non-identifying reasons safe for controlled private diagnostics. */
public enum GraphReadReason {
    NONE,
    OWNER_UNAVAILABLE,
    SOURCE_UNAVAILABLE,
    COVERAGE_UNAVAILABLE,
    SCOPE_MISMATCH,
    REFERENCE_INVALID,
    RETENTION_EXPIRED,
    AUTHORITY_REVOKED,
    GRANT_DENIED,
    POLICY_UNAVAILABLE,
    STORE_UNAVAILABLE,
    CONFLICTING_EVIDENCE,
    DEADLINE_EXCEEDED,
    LIMIT_EXCEEDED,
    TRUNCATED_UNVERIFIED,
    NOT_SUPPORTED
}
