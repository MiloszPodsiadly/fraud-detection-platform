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

        @JsonProperty("events")
        List<AuditEvidenceExportEvent> events
) {
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
