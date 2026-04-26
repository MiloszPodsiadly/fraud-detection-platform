package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuditEventMetadataSummary(
        @JsonProperty("correlation_id")
        String correlationId,

        @JsonProperty("request_id")
        String requestId,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("schema_version")
        String schemaVersion,

        @JsonProperty("failure_category")
        String failureCategory,

        @JsonProperty("failure_reason")
        String failureReason
) {
    private static final int MAX_FIELD_LENGTH = 120;

    public AuditEventMetadataSummary(String correlationId, String failureReason) {
        this(correlationId, null, null, null, null, failureReason);
    }

    static AuditEventMetadataSummary from(AuditEventDocument document) {
        return new AuditEventMetadataSummary(
                safe(document.correlationId()),
                safe(document.requestId()),
                safe(document.sourceService()),
                safe(document.schemaVersion()),
                document.failureCategory() == null ? null : document.failureCategory().name(),
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
