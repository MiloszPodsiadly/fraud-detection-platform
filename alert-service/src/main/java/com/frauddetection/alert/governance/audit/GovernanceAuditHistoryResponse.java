package com.frauddetection.alert.governance.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GovernanceAuditHistoryResponse(
        @JsonProperty("advisory_event_id")
        String advisoryEventId,

        @JsonProperty("status")
        String status,

        @JsonProperty("audit_events")
        List<GovernanceAuditEventResponse> auditEvents
) {
}
