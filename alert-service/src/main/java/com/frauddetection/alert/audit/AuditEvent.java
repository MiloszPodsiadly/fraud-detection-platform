package com.frauddetection.alert.audit;

import java.time.Instant;

public record AuditEvent(
        AuditActor actor,
        AuditAction action,
        AuditResourceType resourceType,
        String resourceId,
        Instant timestamp,
        String correlationId,
        AuditOutcome outcome,
        String failureReason
) {
}
