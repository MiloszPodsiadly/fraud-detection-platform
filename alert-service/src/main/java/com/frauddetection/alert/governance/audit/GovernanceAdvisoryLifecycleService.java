package com.frauddetection.alert.governance.audit;

import org.springframework.stereotype.Service;

@Service
public class GovernanceAdvisoryLifecycleService {

    private final GovernanceAuditRepository repository;

    public GovernanceAdvisoryLifecycleService(GovernanceAuditRepository repository) {
        this.repository = repository;
    }

    public GovernanceAdvisoryLifecycleStatus lifecycleStatus(String advisoryEventId) {
        return repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc(advisoryEventId)
                .map(GovernanceAuditEventDocument::getDecision)
                .map(GovernanceAdvisoryLifecycleStatus::fromLatestDecision)
                .orElse(GovernanceAdvisoryLifecycleStatus.OPEN);
    }
}
