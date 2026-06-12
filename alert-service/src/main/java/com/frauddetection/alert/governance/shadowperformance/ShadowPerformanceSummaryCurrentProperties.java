package com.frauddetection.alert.governance.shadowperformance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shadow-performance.summary.current")
public record ShadowPerformanceSummaryCurrentProperties(
        boolean enabled,
        String path,
        long maxSizeBytes
) {

    private static final long DEFAULT_MAX_SIZE_BYTES = 1_048_576L;

    public ShadowPerformanceSummaryCurrentProperties {
        if (maxSizeBytes <= 0) {
            maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
        }
    }
}
