package com.frauddetection.alert.governance.modelcard;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "model-card.current")
public record ModelCardCurrentProperties(
        boolean enabled,
        String baseDir,
        String path,
        Long maxCardSizeBytes,
        Long maxMarkdownSizeBytes
) {

    private static final String DEFAULT_BASE_DIR = "/run/model-card";
    private static final long DEFAULT_CARD_SIZE_BYTES = 524_288L;
    private static final long DEFAULT_MARKDOWN_SIZE_BYTES = 262_144L;

    public ModelCardCurrentProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = DEFAULT_BASE_DIR;
        }
        if (maxCardSizeBytes == null) {
            maxCardSizeBytes = DEFAULT_CARD_SIZE_BYTES;
        }
        if (maxMarkdownSizeBytes == null) {
            maxMarkdownSizeBytes = DEFAULT_MARKDOWN_SIZE_BYTES;
        }
        if (enabled && (path == null || path.isBlank())) {
            throw new IllegalArgumentException("model-card.current.path must be configured when enabled");
        }
        validateEnabledLimit(enabled, maxCardSizeBytes, DEFAULT_CARD_SIZE_BYTES, "max-card-size-bytes");
        validateEnabledLimit(enabled, maxMarkdownSizeBytes, DEFAULT_MARKDOWN_SIZE_BYTES, "max-markdown-size-bytes");
    }

    private static void validateEnabledLimit(boolean enabled, long value, long maximum, String property) {
        if (enabled && (value < 1 || value > maximum)) {
            throw new IllegalArgumentException(
                    "model-card.current.%s must be between 1 and %d when enabled".formatted(property, maximum));
        }
    }
}
