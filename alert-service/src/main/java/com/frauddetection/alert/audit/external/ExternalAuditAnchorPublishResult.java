package com.frauddetection.alert.audit.external;

public record ExternalAuditAnchorPublishResult(
        int published,
        int duplicates,
        int failed,
        int limit
) {
}
