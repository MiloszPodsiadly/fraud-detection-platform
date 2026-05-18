package com.frauddetection.alert.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fraud.alert.evidence-snapshot")
public record AlertEvidenceSnapshotProperties(Integer maxItems) {

    public static final int DEFAULT_MAX_ITEMS = 50;
    public static final int MIN_MAX_ITEMS = 2;
    public static final int HARD_MAX_ITEMS = 100;

    public AlertEvidenceSnapshotProperties {
        if (maxItems == null) {
            maxItems = DEFAULT_MAX_ITEMS;
        }
        if (maxItems < MIN_MAX_ITEMS) {
            throw new IllegalArgumentException("fraud.alert.evidence-snapshot.max-items must be at least 2");
        }
        if (maxItems > HARD_MAX_ITEMS) {
            throw new IllegalArgumentException("fraud.alert.evidence-snapshot.max-items must not exceed 100");
        }
    }
}
