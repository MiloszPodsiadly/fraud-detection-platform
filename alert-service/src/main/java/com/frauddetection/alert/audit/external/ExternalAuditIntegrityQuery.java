package com.frauddetection.alert.audit.external;

public record ExternalAuditIntegrityQuery(
        String sourceService,
        String partitionKey,
        int limit
) {
}
