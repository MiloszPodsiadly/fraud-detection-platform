package com.frauddetection.alert.audit.read;

import java.time.Instant;
import java.util.Set;

public record ReadAccessAuditEvent(
        String auditId,
        Instant occurredAt,
        String actorId,
        Set<String> actorRoles,
        ReadAccessAuditAction action,
        ReadAccessResourceType resourceType,
        String resourceId,
        ReadAccessEndpointCategory endpointCategory,
        String queryHash,
        Integer page,
        Integer size,
        int resultCount,
        ReadAccessAuditOutcome outcome,
        String correlationId,
        String sourceService,
        int schemaVersion
) {
}
