package com.frauddetection.alert.trust;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;

import java.time.Instant;
import java.util.List;

public record TrustIncidentResponse(
        @JsonProperty("incident_id")
        String incidentId,
        String type,
        String severity,
        String source,
        String fingerprint,
        String status,
        @JsonProperty("first_seen_at")
        Instant firstSeenAt,
        @JsonProperty("last_seen_at")
        Instant lastSeenAt,
        @JsonProperty("occurrence_count")
        long occurrenceCount,
        @JsonProperty("evidence_refs")
        List<String> evidenceRefs,
        @JsonProperty("acknowledged_by")
        String acknowledgedBy,
        @JsonProperty("acknowledged_at")
        Instant acknowledgedAt,
        @JsonProperty("resolved_by")
        String resolvedBy,
        @JsonProperty("resolved_at")
        Instant resolvedAt,
        @JsonProperty("resolution_reason")
        String resolutionReason,
        @JsonProperty("resolution_evidence")
        ResolutionEvidenceReference resolutionEvidence
) {
    public static TrustIncidentResponse from(TrustIncidentDocument document) {
        return new TrustIncidentResponse(
                document.getIncidentId(),
                document.getType(),
                document.getSeverity() == null ? null : document.getSeverity().name(),
                document.getSource(),
                document.getFingerprint(),
                document.getStatus() == null ? null : document.getStatus().name(),
                document.getFirstSeenAt(),
                document.getLastSeenAt(),
                document.getOccurrenceCount(),
                document.getEvidenceRefs() == null ? List.of() : document.getEvidenceRefs(),
                document.getAcknowledgedBy(),
                document.getAcknowledgedAt(),
                document.getResolvedBy(),
                document.getResolvedAt(),
                document.getResolutionReason(),
                document.getResolutionEvidence()
        );
    }
}
