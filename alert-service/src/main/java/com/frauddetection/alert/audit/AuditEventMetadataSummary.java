package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuditEventMetadataSummary(
        @JsonProperty("correlation_id")
        String correlationId,

        @JsonProperty("failure_reason")
        String failureReason
) {
    private static final int MAX_FIELD_LENGTH = 120;

    static AuditEventMetadataSummary from(AuditEventDocument document) {
        return new AuditEventMetadataSummary(
                safe(document.correlationId()),
                safe(document.failureReason())
        );
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (normalized.length() <= MAX_FIELD_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_FIELD_LENGTH);
    }
}
