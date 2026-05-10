package com.frauddetection.alert.domain;

public enum FraudCaseAuditAction {
    CASE_CREATED,
    CASE_ASSIGNED,
    CASE_REASSIGNED,
    NOTE_ADDED,
    DECISION_ADDED,
    STATUS_CHANGED,
    CASE_CLOSED,
    CASE_REOPENED
}
