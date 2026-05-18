package com.frauddetection.alert.evidence;

import java.time.Instant;
import java.util.Objects;

public record EvidenceSnapshotItem(
        String reasonCode,
        EvidenceType evidenceType,
        EvidenceSeverity severity,
        EvidenceSource source,
        EvidenceStatus status,
        String title,
        String description,
        String value,
        String baselineValue,
        Instant observedAt
) {
    public EvidenceSnapshotItem {
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(status, "status is required");
    }
}
