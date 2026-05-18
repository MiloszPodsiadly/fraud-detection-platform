package com.frauddetection.common.events.evidence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ScoringEvidenceItem(
        String evidenceId,
        String reasonCode,
        ScoringEvidenceType evidenceType,
        ScoringEvidenceSource source,
        ScoringEvidenceStatus status,
        ScoringEvidenceSeverity severity,
        String title,
        String description,
        String value,
        String baselineValue,
        Map<String, Object> attributes,
        Instant observedAt
) {
    public ScoringEvidenceItem {
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(status, "status is required");
        attributes = ScoringEvidenceAttributes.safeCopy(attributes);
    }
}
