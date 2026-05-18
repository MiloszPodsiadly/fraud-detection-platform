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

    public EvidenceSnapshotItem(
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
        this(
                null,
                null,
                null,
                null,
                reasonCode,
                evidenceType,
                source,
                status,
                severity,
                title,
                description,
                value,
                baselineValue,
                diagnosticAttributes(evidenceType),
                legacyObservedAt(evidenceType, severity, source, status, observedAt),
                legacyObservedAt(evidenceType, severity, source, status, observedAt),
                null,
                null,
                null,
                null
        );
    }

    private static Map<String, Object> diagnosticAttributes(EvidenceType evidenceType) {
        if (evidenceType == EvidenceType.DIAGNOSTIC) {
            return Map.of(
                    "diagnostic", true,
                    "supportedEvidenceCreated", false,
                    "reasonCodeApplicable", false
            );
        }
        return Map.of();
    }

    private static Instant legacyObservedAt(
            EvidenceType evidenceType,
            EvidenceSeverity severity,
            EvidenceSource source,
            EvidenceStatus status,
            Instant observedAt
    ) {
        Objects.requireNonNull(evidenceType, "evidenceType is required");
        Objects.requireNonNull(severity, "severity is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(status, "status is required");
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt is required for legacy EvidenceSnapshotItem constructor");
        }
        return observedAt;
    }

    private static boolean isUnknownReasonCode(String reasonCode) {
        return reasonCode != null
                && "UNKNOWN".equals(reasonCode.trim().toUpperCase(Locale.ROOT));
    }
}
