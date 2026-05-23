package com.frauddetection.alert.api;

import java.time.Instant;
import java.util.List;

public record FraudCaseEvidenceTimelineResponse(
        String caseId,
        List<FraudCaseTimelineEventResponse> events,
        boolean partial,
        boolean legacy,
        boolean truncated,
        String truncationReason,
        Instant generatedAt
) {
    public FraudCaseEvidenceTimelineResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
