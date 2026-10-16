package com.frauddetection.alert.governance.exactmodelshadowperformance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "model-shadow-performance.artifacts")
public record ExactModelShadowPerformanceProperties(boolean enabled, String baseDir, Long maxSummarySizeBytes) {

    private static final String DEFAULT_BASE_DIR = "/run/model-shadow-performance";
    private static final long DEFAULT_MAX_SUMMARY_SIZE_BYTES = 524_288L;

    public ExactModelShadowPerformanceProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = DEFAULT_BASE_DIR;
        }
        if (maxSummarySizeBytes == null) {
            maxSummarySizeBytes = DEFAULT_MAX_SUMMARY_SIZE_BYTES;
        }
        if (enabled && (maxSummarySizeBytes < 1 || maxSummarySizeBytes > DEFAULT_MAX_SUMMARY_SIZE_BYTES)) {
            throw new IllegalArgumentException(
                    "model-shadow-performance.artifacts.max-summary-size-bytes must be between 1 and 524288");
        }
    }
}
