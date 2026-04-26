package com.frauddetection.alert.governance.audit;

public record GovernanceAdvisoryQuery(
        String severity,
        String modelVersion,
        Integer limit
) {
}
