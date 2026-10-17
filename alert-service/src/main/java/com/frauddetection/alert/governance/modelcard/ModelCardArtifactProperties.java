package com.frauddetection.alert.governance.modelcard;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "model-card.artifacts")
public record ModelCardArtifactProperties(
        boolean enabled,
        String baseDir,
        Long maxCardSizeBytes,
        Long maxMarkdownSizeBytes
) {

    private static final String DEFAULT_BASE_DIR = "/run/model-card-artifacts";
    private static final long DEFAULT_CARD_SIZE_BYTES = 524_288L;
    private static final long DEFAULT_MARKDOWN_SIZE_BYTES = 262_144L;

    public ModelCardArtifactProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = DEFAULT_BASE_DIR;
        }
        if (maxCardSizeBytes == null) {
            maxCardSizeBytes = DEFAULT_CARD_SIZE_BYTES;
        }
        if (maxMarkdownSizeBytes == null) {
            maxMarkdownSizeBytes = DEFAULT_MARKDOWN_SIZE_BYTES;
        }
        validateEnabledLimit(enabled, maxCardSizeBytes, DEFAULT_CARD_SIZE_BYTES, "max-card-size-bytes");
        validateEnabledLimit(enabled, maxMarkdownSizeBytes, DEFAULT_MARKDOWN_SIZE_BYTES, "max-markdown-size-bytes");
    }

    private static void validateEnabledLimit(boolean enabled, long value, long maximum, String property) {
        if (enabled && (value < 1 || value > maximum)) {
            throw new IllegalArgumentException(
                    "model-card.artifacts.%s must be between 1 and %d when enabled".formatted(property, maximum));
        }
    }
}
