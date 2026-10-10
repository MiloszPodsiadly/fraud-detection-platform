package com.frauddetection.alert.audit.external.integrity;

public record ExternalAuditIntegrityQuery(
        String sourceService,
        String partitionKey,
        int limit,
        Long fromPosition
) {
}
