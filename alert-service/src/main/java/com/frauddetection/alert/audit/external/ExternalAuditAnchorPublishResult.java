package com.frauddetection.alert.audit.external;

public record ExternalAuditAnchorPublishResult(
        int published,
        int partial,
        int duplicates,
        int failed,
        int limit
) {
}
