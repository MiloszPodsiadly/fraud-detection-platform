package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record AuditEvidenceExportResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("count")
        int count,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("from")
        Instant from,

        @JsonProperty("to")
        Instant to,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,

        @JsonProperty("external_anchor_status")
        String externalAnchorStatus,

        @JsonProperty("anchor_coverage")
        AnchorCoverage anchorCoverage,

        @JsonProperty("export_fingerprint")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String exportFingerprint,

        @JsonProperty("chain_range_start")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long chainRangeStart,

        @JsonProperty("chain_range_end")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long chainRangeEnd,

        @JsonProperty("predecessor_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String predecessorHash,

        @JsonProperty("partial_chain_range")
        boolean partialChainRange,

        @JsonProperty("events")
        List<AuditEvidenceExportEvent> events
) {
    public AuditEvidenceExportResponse(
            String status,
            int count,
            int limit,
            String sourceService,
            Instant from,
            Instant to,
            String reasonCode,
            String message,
            String externalAnchorStatus,
            AnchorCoverage anchorCoverage,
            List<AuditEvidenceExportEvent> events
    ) {
        this(status, count, limit, sourceService, from, to, reasonCode, message, externalAnchorStatus, anchorCoverage, null, null, null, null, false, events);
    }

    static AuditEvidenceExportResponse unavailable(AuditEvidenceExportQuery query) {
        return new AuditEvidenceExportResponse(
                "UNAVAILABLE",
                0,
                query.limit(),
                query.sourceService(),
                query.from(),
                query.to(),
                "INTERNAL_ERROR",
                "Audit evidence store is currently unavailable.",
                "UNAVAILABLE",
                AnchorCoverage.empty(),
                null,
                null,
                null,
                null,
                false,
                List.of()
        );
    }

    public record AnchorCoverage(
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
        public static AnchorCoverage empty() {
            return new AnchorCoverage(0, 0, 0, 0, 1.0d);
        }
    }
}
