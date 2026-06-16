package com.frauddetection.alert.governance.promotionreviewreadiness;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "promotion-review-readiness.current")
public record PromotionReviewReadinessReportCurrentProperties(
        boolean enabled,
        String baseDir,
        String path,
        long maxSizeBytes
) {

    private static final String DEFAULT_BASE_DIR = "/run/promotion-readiness";
    private static final long DEFAULT_MAX_SIZE_BYTES = 262_144L;

    public PromotionReviewReadinessReportCurrentProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = DEFAULT_BASE_DIR;
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
    }
}
