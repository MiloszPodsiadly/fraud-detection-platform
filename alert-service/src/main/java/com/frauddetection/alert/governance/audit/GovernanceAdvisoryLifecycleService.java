package com.frauddetection.alert.governance.audit;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class GovernanceAdvisoryLifecycleService {

    private final GovernanceAuditRepository repository;

    public GovernanceAdvisoryLifecycleService(GovernanceAuditRepository repository) {
        this.repository = repository;
    }

    public GovernanceAdvisoryLifecycleStatus lifecycleStatus(String advisoryEventId) {
        // Lifecycle status is a derived projection from audit events.
        // It MUST NOT be persisted or used as a source of truth.
        try {
            return repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc(advisoryEventId)
                    .map(GovernanceAuditEventDocument::getDecision)
                    .map(GovernanceAdvisoryLifecycleStatus::fromLatestDecision)
                    .orElse(GovernanceAdvisoryLifecycleStatus.OPEN);
        } catch (DataAccessException exception) {
            return GovernanceAdvisoryLifecycleStatus.OPEN;
        }
    }
}
