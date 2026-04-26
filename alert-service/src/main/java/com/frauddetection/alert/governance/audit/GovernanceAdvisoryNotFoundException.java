package com.frauddetection.alert.governance.audit;

public class GovernanceAdvisoryNotFoundException extends RuntimeException {

    public GovernanceAdvisoryNotFoundException(String eventId) {
        super("Governance advisory event not found: " + eventId);
    }
}
