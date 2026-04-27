package com.frauddetection.alert.audit.external;

import java.time.Instant;

public record AuditEvidenceExportQuery(
        Instant from,
        Instant to,
        String sourceService,
        String partitionKey,
        int limit
) {
}
