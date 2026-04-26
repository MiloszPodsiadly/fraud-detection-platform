package com.frauddetection.alert.audit;

public enum AuditFailureCategory {
    NONE,
    VALIDATION,
    AUTHORIZATION,
    DEPENDENCY,
    CONFLICT,
    UNKNOWN
}
