package com.frauddetection.alert.audit.external;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ExternalAuditAnchorCoverageResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("latest_local_position")
        long latestLocalPosition,

        @JsonProperty("latest_external_position")
        long latestExternalPosition,

        @JsonProperty("position_lag")
        long positionLag,

        @JsonProperty("time_lag_seconds")
        Long timeLagSeconds,

        @JsonProperty("missing_ranges")
        List<ExternalAuditAnchorMissingRange> missingRanges,

        @JsonProperty("truncated")
        boolean truncated,

        @JsonProperty("limit")
        int limit,

        @JsonProperty("reason_code")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String reasonCode,

        @JsonProperty("message")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message
) {
}
