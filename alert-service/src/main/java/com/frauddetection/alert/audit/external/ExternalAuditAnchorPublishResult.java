package com.frauddetection.alert.audit.external;

public record ExternalAuditAnchorPublishResult(
        int published,
        int partial,
        int duplicates,
        int failed,
        int limit,
        int localStatusUnverified
) {
    public ExternalAuditAnchorPublishResult(
            int published,
            int partial,
            int duplicates,
            int failed,
            int limit
    ) {
        this(published, partial, duplicates, failed, limit, 0);
    }
}
