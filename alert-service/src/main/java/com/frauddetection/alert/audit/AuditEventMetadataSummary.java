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
        String failureReason,

        @JsonProperty("endpoint_action")
        String endpointAction,

        @JsonProperty("filters_summary")
        String filtersSummary,

        @JsonProperty("count_returned")
        Integer countReturned
) {
    private static final int MAX_FIELD_LENGTH = 120;

    public AuditEventMetadataSummary {
        correlationId = safe(correlationId);
        requestId = safe(requestId);
        sourceService = safe(sourceService);
        schemaVersion = safe(schemaVersion);
        failureCategory = safe(failureCategory);
        failureReason = safe(failureReason);
        endpointAction = safe(endpointAction);
        filtersSummary = safe(filtersSummary);
        countReturned = countReturned == null ? null : Math.max(0, Math.min(countReturned, 500));
    }

    public AuditEventMetadataSummary(String correlationId, String failureReason) {
        this(correlationId, null, null, null, null, failureReason, null, null, null);
    }

    public static AuditEventMetadataSummary auditRead(
            String correlationId,
            String sourceService,
            String schemaVersion,
            String endpointAction,
            String filtersSummary,
            Integer countReturned
    ) {
        return new AuditEventMetadataSummary(
                correlationId,
                null,
                sourceService,
                schemaVersion,
                null,
                null,
                endpointAction,
                filtersSummary,
                countReturned
        );
    }

    static AuditEventMetadataSummary from(AuditEventDocument document) {
        if (document.metadataSummary() != null) {
            return document.metadataSummary();
        }
        return new AuditEventMetadataSummary(
                safe(document.correlationId()),
                safe(document.requestId()),
                safe(document.sourceService()),
                safe(document.schemaVersion()),
                document.failureCategory() == null ? null : document.failureCategory().name(),
                safe(document.failureReason()),
                null,
                null,
                null
        );
    }

    static String safe(String value) {
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
