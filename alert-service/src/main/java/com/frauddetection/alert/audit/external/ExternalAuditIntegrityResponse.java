package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.alert.audit.AuditIntegrityViolation;

import java.util.List;

public record ExternalAuditIntegrityResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("checked")
        int checked,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("source_service")
        String sourceService,

        @JsonProperty("partition_key")
        String partitionKey,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,

        @JsonProperty("local_anchor")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExternalAuditAnchorSummary localAnchor,

        @JsonProperty("external_anchor")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ExternalAuditAnchorSummary externalAnchor,

        @JsonProperty("violations")
        List<AuditIntegrityViolation> violations
) {
    static ExternalAuditIntegrityResponse unavailable(ExternalAuditIntegrityQuery query, String reasonCode, String message) {
        return new ExternalAuditIntegrityResponse(
                "UNAVAILABLE",
                0,
                query.limit(),
                query.sourceService(),
                query.partitionKey(),
                reasonCode,
                message,
                null,
                null,
                List.of()
        );
    }
}
