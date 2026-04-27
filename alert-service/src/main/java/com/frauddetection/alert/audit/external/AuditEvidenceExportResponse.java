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
                List.of()
        );
    }
}
