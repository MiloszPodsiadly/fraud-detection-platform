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
        Objects.requireNonNull(evidenceType, "evidenceType is required");
        Objects.requireNonNull(severity, "severity is required");
        Objects.requireNonNull(observedAt, "observedAt is required");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);

        if (status == ScoringEvidenceStatus.AVAILABLE) {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode is required for AVAILABLE scoring evidence");
            }
            if ("UNKNOWN".equals(reasonCode)) {
                throw new IllegalArgumentException("UNKNOWN cannot be AVAILABLE scoring evidence");
            }
            if (evidenceType == ScoringEvidenceType.DIAGNOSTIC) {
                throw new IllegalArgumentException("AVAILABLE scoring evidence cannot be DIAGNOSTIC");
            }
        }
        if (evidenceType == ScoringEvidenceType.DIAGNOSTIC) {
            if (!Boolean.TRUE.equals(attributes.get("diagnostic"))
                    || !Boolean.FALSE.equals(attributes.get("supportedEvidenceCreated"))
                    || !Boolean.FALSE.equals(attributes.get("reasonCodeApplicable"))) {
                throw new IllegalArgumentException("DIAGNOSTIC scoring evidence requires diagnostic metadata");
            }
        }
    }
}
