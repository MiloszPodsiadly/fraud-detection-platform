package com.frauddetection.alert.governance.audit;

public record GovernanceAdvisorySnapshot(
        String eventId,
        String modelName,
        String modelVersion,
        String severity,
        String confidence,
        String advisoryConfidenceContext
) {
}
