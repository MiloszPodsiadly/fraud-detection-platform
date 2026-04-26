package com.frauddetection.alert.audit;

import java.time.Instant;

public record AuditEvent(
        AuditActor actor,
        AuditAction action,
        AuditResourceType resourceType,
        String resourceId,
        Instant timestamp,
        String correlationId,
        String requestId,
        AuditOutcome outcome,
        AuditFailureCategory failureCategory,
        String failureReason
) {
    public AuditEvent(
            AuditActor actor,
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            Instant timestamp,
            String correlationId,
            AuditOutcome outcome,
            String failureReason
    ) {
        this(
                actor,
                action,
                resourceType,
                resourceId,
                timestamp,
                correlationId,
                null,
                outcome,
                failureCategory(outcome, failureReason),
                failureReason
        );
    }

    static AuditFailureCategory failureCategory(AuditOutcome outcome, String failureReason) {
        if (outcome == AuditOutcome.SUCCESS) {
            return AuditFailureCategory.NONE;
        }
        if (failureReason == null || failureReason.isBlank()) {
            return AuditFailureCategory.UNKNOWN;
        }
        String normalized = failureReason.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("AUTH") || normalized.contains("AUTHORITY") || normalized.contains("FORBIDDEN")) {
            return AuditFailureCategory.AUTHORIZATION;
        }
        if (normalized.contains("VALID") || normalized.contains("INVALID") || normalized.contains("BAD_REQUEST")) {
            return AuditFailureCategory.VALIDATION;
        }
        if (normalized.contains("CONFLICT")) {
            return AuditFailureCategory.CONFLICT;
        }
        if (normalized.contains("DEPENDENCY") || normalized.contains("UNAVAILABLE") || normalized.contains("TIMEOUT")) {
            return AuditFailureCategory.DEPENDENCY;
        }
        return AuditFailureCategory.UNKNOWN;
    }
}
