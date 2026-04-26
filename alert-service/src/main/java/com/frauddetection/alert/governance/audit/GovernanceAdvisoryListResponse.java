package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GovernanceAdvisoryListResponse(
        @JsonProperty("status")
        String status,

        @JsonProperty("count")
        int count,

        @JsonProperty("retention_limit")
        int retentionLimit,

        @JsonProperty("advisory_events")
        List<GovernanceAdvisoryEvent> advisoryEvents
) {
}
