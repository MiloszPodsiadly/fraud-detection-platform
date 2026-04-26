package com.frauddetection.alert.governance.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GovernanceAuditRepository extends MongoRepository<GovernanceAuditEventDocument, String> {

    List<GovernanceAuditEventDocument> findByAdvisoryEventIdOrderByCreatedAtDesc(String advisoryEventId, Pageable pageable);

    Optional<GovernanceAuditEventDocument> findFirstByAdvisoryEventIdOrderByCreatedAtDesc(String advisoryEventId);

    List<GovernanceAuditEventDocument> findByAdvisoryEventIdOrderByCreatedAtAsc(String advisoryEventId);

    List<GovernanceAuditEventDocument> findByCreatedAtBetweenOrderByCreatedAtAsc(Instant from, Instant to, Pageable pageable);

    long countByAdvisoryEventId(String advisoryEventId);
}
