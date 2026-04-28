package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record GovernanceAdvisoryListResponse(
        @JsonProperty("status")
        String status,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("reason_code")
        String reasonCode,

        @JsonProperty("count")
        int count,

        @JsonProperty("retention_limit")
        int retentionLimit,

        @JsonProperty("advisory_events")
        List<GovernanceAdvisoryEvent> advisoryEvents
) {
    public GovernanceAdvisoryListResponse(
            String status,
            int count,
            int retentionLimit,
            List<GovernanceAdvisoryEvent> advisoryEvents
    ) {
        this(status, null, count, retentionLimit, advisoryEvents);
    }
}
