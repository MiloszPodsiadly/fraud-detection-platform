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
                "AUDIT_STORE_UNAVAILABLE",
                "Audit evidence store is currently unavailable.",
                "UNAVAILABLE",
                AnchorCoverage.empty(),
                List.of()
        );
    }

    public record AnchorCoverage(
            @JsonProperty("local_anchors_available_count")
            int localAnchorsAvailableCount,

            @JsonProperty("external_anchors_available_count")
            int externalAnchorsAvailableCount,

            @JsonProperty("events_without_local_anchor_count")
            int eventsWithoutLocalAnchorCount,

            @JsonProperty("events_without_external_anchor_count")
            int eventsWithoutExternalAnchorCount
    ) {
        public static AnchorCoverage empty() {
            return new AnchorCoverage(0, 0, 0, 0);
        }
    }
}
