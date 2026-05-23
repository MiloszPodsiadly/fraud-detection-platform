package com.frauddetection.alert.api;

import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;

import java.time.Instant;

public record FraudCaseTimelineEventResponse(
        String eventKey,
        FraudCaseTimelineEventType eventType,
        Instant occurredAt,
        EvidenceSource source,
        EvidenceStatus evidenceStatus,
        String title,
        String description,
        FraudCaseTimelineLinkedEntityType linkedEntityType,
        boolean approximateTime
) {
}
