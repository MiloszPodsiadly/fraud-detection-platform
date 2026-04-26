package com.frauddetection.alert.governance.audit;

public interface GovernanceAdvisoryClient {

    GovernanceAdvisorySnapshot getAdvisory(String eventId);

    GovernanceAdvisoryEvent getAdvisoryEvent(String eventId);

    GovernanceAdvisoryListResponse listAdvisories(GovernanceAdvisoryQuery query);
}
