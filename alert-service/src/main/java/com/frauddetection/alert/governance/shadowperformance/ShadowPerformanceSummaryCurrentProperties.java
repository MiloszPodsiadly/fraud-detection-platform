package com.frauddetection.alert.governance.shadowperformance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shadow-performance.summary.current")
public record ShadowPerformanceSummaryCurrentProperties(
        boolean enabled,
        String baseDir,
        String path,
        long maxSizeBytes
) {

    private static final String DEFAULT_BASE_DIR = "/run/shadow-performance";
    private static final long DEFAULT_MAX_SIZE_BYTES = 1_048_576L;

    public ShadowPerformanceSummaryCurrentProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = DEFAULT_BASE_DIR;
        }
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
    }
}
