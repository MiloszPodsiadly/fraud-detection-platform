package com.frauddetection.alert.governance.modelcard;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "model-card.current")
public record ModelCardCurrentProperties(
        boolean enabled,
        String baseDir,
        String path,
        long maxCardSizeBytes,
        long maxMarkdownSizeBytes
) {

    private static final String DEFAULT_BASE_DIR = "/run/model-card";
    private static final long DEFAULT_CARD_SIZE_BYTES = 524_288L;
    private static final long DEFAULT_MARKDOWN_SIZE_BYTES = 262_144L;

    public ModelCardCurrentProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = DEFAULT_BASE_DIR;
        }
        if (maxCardSizeBytes <= 0) {
            maxCardSizeBytes = DEFAULT_CARD_SIZE_BYTES;
        }
        if (maxMarkdownSizeBytes <= 0) {
            maxMarkdownSizeBytes = DEFAULT_MARKDOWN_SIZE_BYTES;
        }
    }
}
