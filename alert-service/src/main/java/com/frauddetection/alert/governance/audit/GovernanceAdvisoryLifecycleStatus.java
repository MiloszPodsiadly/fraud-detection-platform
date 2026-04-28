package com.frauddetection.alert.governance.audit;

public enum GovernanceAdvisoryLifecycleStatus {
    OPEN,
    UNKNOWN,
    ACKNOWLEDGED,
    NEEDS_FOLLOW_UP,
    DISMISSED_AS_NOISE;

    public static GovernanceAdvisoryLifecycleStatus fromLatestDecision(GovernanceAuditDecision decision) {
        if (decision == null) {
            return OPEN;
        }
        return switch (decision) {
            case ACKNOWLEDGED -> ACKNOWLEDGED;
            case NEEDS_FOLLOW_UP -> NEEDS_FOLLOW_UP;
            case DISMISSED_AS_NOISE -> DISMISSED_AS_NOISE;
        };
    }
}
