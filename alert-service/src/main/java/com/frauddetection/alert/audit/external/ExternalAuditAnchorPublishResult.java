package com.frauddetection.alert.audit.external;

public record ExternalAuditAnchorPublishResult(
        int published,
        int partial,
        int duplicates,
        int failed,
        int limit,
        int localStatusUnverified,
        int recovered,
        int stillUnverified,
        int missing,
        int invalid
) {
    public ExternalAuditAnchorPublishResult(
            int published,
            int partial,
            int duplicates,
            int failed,
            int limit
    ) {
        this(published, partial, duplicates, failed, limit, 0, 0, 0, 0, 0);
    }

    public ExternalAuditAnchorPublishResult(
            int published,
            int partial,
            int duplicates,
            int failed,
            int limit,
            int localStatusUnverified
    ) {
        this(published, partial, duplicates, failed, limit, localStatusUnverified, 0, 0, 0, 0);
    }

    ExternalAuditAnchorPublishResult plus(ExternalAuditAnchorPublishResult other) {
        return new ExternalAuditAnchorPublishResult(
                published + other.published,
                partial + other.partial,
                duplicates + other.duplicates,
                failed + other.failed,
                Math.max(limit, other.limit),
                localStatusUnverified + other.localStatusUnverified,
                recovered + other.recovered,
                stillUnverified + other.stillUnverified,
                missing + other.missing,
                invalid + other.invalid
        );
    }
}
