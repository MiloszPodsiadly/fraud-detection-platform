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
        String message,

        @JsonProperty("coverage_status")
        String coverageStatus,

        @JsonProperty("max_position_lag")
        long maxPositionLag,

        @JsonProperty("max_time_lag_seconds")
        Long maxTimeLagSeconds,

        @JsonProperty("max_missing_ranges")
        int maxMissingRanges
) {
    public ExternalAuditAnchorCoverageResponse(
            String status,
            long latestLocalPosition,
            long latestExternalPosition,
            long positionLag,
            Long timeLagSeconds,
            List<ExternalAuditAnchorMissingRange> missingRanges,
            boolean truncated,
            int limit,
            String reasonCode,
            String message
    ) {
        this(
                status,
                latestLocalPosition,
                latestExternalPosition,
                positionLag,
                timeLagSeconds,
                missingRanges,
                truncated,
                limit,
                reasonCode,
                message,
                coverageStatus(positionLag, timeLagSeconds, missingRanges, truncated),
                0,
                null,
                50
        );
    }

    private static String coverageStatus(
            long positionLag,
            Long timeLagSeconds,
            List<ExternalAuditAnchorMissingRange> missingRanges,
            boolean truncated
    ) {
        if (truncated || positionLag > 0 || (timeLagSeconds != null && timeLagSeconds > 0) || (missingRanges != null && !missingRanges.isEmpty())) {
            return "DEGRADED";
        }
        return "HEALTHY";
    }
}
