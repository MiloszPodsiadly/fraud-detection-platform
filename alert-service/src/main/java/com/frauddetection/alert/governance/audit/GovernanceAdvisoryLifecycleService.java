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
            GovernanceAdvisoryLifecycleStatus status = repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc(advisoryEventId)
                    .map(GovernanceAuditEventDocument::getDecision)
                    .map(GovernanceAdvisoryLifecycleStatus::fromLatestDecision)
                    .orElse(GovernanceAdvisoryLifecycleStatus.OPEN);
            assertAuditUnavailableNeverReturnsOpen(true, status);
            return status;
        } catch (DataAccessException exception) {
            GovernanceAdvisoryLifecycleStatus status = GovernanceAdvisoryLifecycleStatus.UNKNOWN;
            assertAuditUnavailableNeverReturnsOpen(false, status);
            return status;
        }
    }

    private void assertAuditUnavailableNeverReturnsOpen(
            boolean auditAvailable,
            GovernanceAdvisoryLifecycleStatus status
    ) {
        if (!auditAvailable && status == GovernanceAdvisoryLifecycleStatus.OPEN) {
            throw new IllegalStateException("Lifecycle status OPEN requires available audit source.");
        }
    }
}
