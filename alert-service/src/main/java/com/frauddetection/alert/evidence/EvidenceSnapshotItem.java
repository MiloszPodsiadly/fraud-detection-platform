package com.frauddetection.alert.evidence;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record EvidenceSnapshotItem(
        String evidenceId,
        String sourceEventId,
        String transactionId,
        String correlationId,
        String reasonCode,
        EvidenceType evidenceType,
        EvidenceSource source,
        EvidenceStatus status,
        EvidenceSeverity severity,
        String title,
        String description,
        String value,
        String baselineValue,
        Map<String, Object> attributes,
        Instant observedAt,
        Instant projectedAt,
        String scoringStrategy,
        String modelName,
        String modelVersion,
        Instant inferenceTimestamp
) {
    public EvidenceSnapshotItem {
        Objects.requireNonNull(evidenceType, "evidenceType is required");
        Objects.requireNonNull(severity, "severity is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(projectedAt, "projectedAt is required");
        attributes = AlertEvidenceSnapshotAttributes.safeCopy(attributes);

        if (status == EvidenceStatus.AVAILABLE) {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode is required for AVAILABLE evidence snapshot");
            }
            if (isUnknownReasonCode(reasonCode)) {
                throw new IllegalArgumentException("UNKNOWN cannot be AVAILABLE evidence snapshot");
            }
            if (evidenceType == EvidenceType.DIAGNOSTIC) {
                throw new IllegalArgumentException("AVAILABLE evidence snapshot cannot be DIAGNOSTIC");
            }
        }
        if (evidenceType == EvidenceType.DIAGNOSTIC) {
            if (!Boolean.TRUE.equals(attributes.get("diagnostic"))
                    || !Boolean.FALSE.equals(attributes.get("supportedEvidenceCreated"))
                    || !Boolean.FALSE.equals(attributes.get("reasonCodeApplicable"))) {
                throw new IllegalArgumentException("DIAGNOSTIC evidence snapshot requires diagnostic metadata");
            }
        }
    }

    private static boolean isUnknownReasonCode(String reasonCode) {
        return reasonCode != null
                && "UNKNOWN".equals(reasonCode.trim().toUpperCase(Locale.ROOT));
    }
}
