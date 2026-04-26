package com.frauddetection.alert.audit;

import java.time.Instant;

record AuditEventQuery(
        AuditAction eventType,
        String actorId,
        AuditResourceType resourceType,
        String resourceId,
        Instant from,
        Instant to,
        int limit
) {
}
