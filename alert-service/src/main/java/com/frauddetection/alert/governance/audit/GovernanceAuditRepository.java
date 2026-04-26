package com.frauddetection.alert.governance.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GovernanceAuditRepository extends Repository<GovernanceAuditEventDocument, String> {

    GovernanceAuditEventDocument save(GovernanceAuditEventDocument document);

    List<GovernanceAuditEventDocument> findByAdvisoryEventIdOrderByCreatedAtDesc(String advisoryEventId, Pageable pageable);

    Optional<GovernanceAuditEventDocument> findFirstByAdvisoryEventIdOrderByCreatedAtDesc(String advisoryEventId);

    List<GovernanceAuditEventDocument> findByCreatedAtBetweenOrderByCreatedAtAsc(Instant from, Instant to, Pageable pageable);
}
