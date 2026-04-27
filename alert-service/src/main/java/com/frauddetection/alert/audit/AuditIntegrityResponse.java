package com.frauddetection.alert.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AuditIntegrityResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("checked")
        int checked,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,

        @JsonProperty("first_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String firstEventHash,

        @JsonProperty("last_event_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String lastEventHash,

        @JsonProperty("partition_key")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String partitionKey,

        @JsonProperty("last_anchor_hash")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String lastAnchorHash,

        @JsonProperty("violations")
        List<AuditIntegrityViolation> violations
) {
    static AuditIntegrityResponse unavailable(int limit) {
        return new AuditIntegrityResponse(
                "UNAVAILABLE",
                0,
                limit,
                "AUDIT_STORE_UNAVAILABLE",
                "Audit event store is currently unavailable.",
                null,
                null,
                null,
                null,
                List.of()
        );
    }
}
