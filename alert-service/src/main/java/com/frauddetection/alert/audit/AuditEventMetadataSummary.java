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
        Integer countReturned,

        @JsonProperty("from")
        String from,

        @JsonProperty("to")
        String to,

        @JsonProperty("limit")
        Integer limit,

        @JsonProperty("returned_count")
        Integer returnedCount,

        @JsonProperty("export_status")
        String exportStatus,

        @JsonProperty("reason_code")
        String reasonCode,

        @JsonProperty("external_anchor_status")
        String externalAnchorStatus,

        @JsonProperty("anchor_coverage")
        AnchorCoverageSummary anchorCoverage,

        @JsonProperty("export_fingerprint")
        String exportFingerprint,

        @JsonProperty("trust_level")
        String trustLevel,

        @JsonProperty("internal_integrity_status")
        String internalIntegrityStatus,

        @JsonProperty("external_integrity_status")
        String externalIntegrityStatus,

        @JsonProperty("attestation_fingerprint")
        String attestationFingerprint
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
        from = safe(from);
        to = safe(to);
        limit = limit == null ? null : Math.max(0, Math.min(limit, 500));
        returnedCount = returnedCount == null ? null : Math.max(0, Math.min(returnedCount, 500));
        exportStatus = safe(exportStatus);
        reasonCode = safe(reasonCode);
        externalAnchorStatus = safe(externalAnchorStatus);
        exportFingerprint = safe(exportFingerprint);
        trustLevel = safe(trustLevel);
        internalIntegrityStatus = safe(internalIntegrityStatus);
        externalIntegrityStatus = safe(externalIntegrityStatus);
        attestationFingerprint = safe(attestationFingerprint);
    }

    public AuditEventMetadataSummary(String correlationId, String failureReason) {
        this(correlationId, null, null, null, null, failureReason, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    public AuditEventMetadataSummary(
            String correlationId,
            String requestId,
            String sourceService,
            String schemaVersion,
            String failureCategory,
            String failureReason,
            String endpointAction,
            String filtersSummary,
            Integer countReturned
    ) {
        this(correlationId, requestId, sourceService, schemaVersion, failureCategory, failureReason, endpointAction, filtersSummary, countReturned,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
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

    public static AuditEventMetadataSummary evidenceExport(
            String sourceService,
            String schemaVersion,
            String from,
            String to,
            Integer limit,
            Integer returnedCount,
            String exportStatus,
            String reasonCode,
            String externalAnchorStatus,
            AnchorCoverageSummary anchorCoverage,
            String exportFingerprint
    ) {
        return new AuditEventMetadataSummary(
                null,
                null,
                sourceService,
                schemaVersion,
                null,
                safe(reasonCode),
                "GET /api/v1/audit/evidence/export",
                "source_service=" + safe(sourceService) + ";from=present;to=present;limit=" + limit,
                returnedCount,
                from,
                to,
                limit,
                returnedCount,
                exportStatus,
                reasonCode,
                externalAnchorStatus,
                anchorCoverage,
                exportFingerprint,
                null,
                null,
                null,
                null
        );
    }

    public static AuditEventMetadataSummary trustAttestation(
            String sourceService,
            String schemaVersion,
            Integer limit,
            String trustLevel,
            String internalIntegrityStatus,
            String externalIntegrityStatus,
            String externalAnchorStatus,
            String attestationFingerprint
    ) {
        return new AuditEventMetadataSummary(
                null,
                null,
                sourceService,
                schemaVersion,
                null,
                null,
                "GET /api/v1/audit/trust/attestation",
                "source_service=" + safe(sourceService) + ";limit=" + limit,
                null,
                null,
                null,
                limit,
                null,
                null,
                null,
                externalAnchorStatus,
                null,
                null,
                trustLevel,
                internalIntegrityStatus,
                externalIntegrityStatus,
                attestationFingerprint
        );
    }

    public static AuditEventMetadataSummary from(AuditEventDocument document) {
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

    public record AnchorCoverageSummary(
            @JsonProperty("total_events")
            int totalEvents,

            @JsonProperty("events_with_local_anchor")
            int eventsWithLocalAnchor,

            @JsonProperty("events_with_external_anchor")
            int eventsWithExternalAnchor,

            @JsonProperty("events_missing_external_anchor")
            int eventsMissingExternalAnchor,

            @JsonProperty("coverage_ratio")
            double coverageRatio
    ) {
        public AnchorCoverageSummary {
            totalEvents = Math.max(0, Math.min(totalEvents, 500));
            eventsWithLocalAnchor = Math.max(0, Math.min(eventsWithLocalAnchor, 500));
            eventsWithExternalAnchor = Math.max(0, Math.min(eventsWithExternalAnchor, 500));
            eventsMissingExternalAnchor = Math.max(0, Math.min(eventsMissingExternalAnchor, 500));
            coverageRatio = Math.max(0.0d, Math.min(coverageRatio, 1.0d));
        }
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
