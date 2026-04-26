package com.frauddetection.alert.governance.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface GovernanceAuditRepository extends MongoRepository<GovernanceAuditEventDocument, String> {

    List<GovernanceAuditEventDocument> findByAdvisoryEventIdOrderByCreatedAtDesc(String advisoryEventId, Pageable pageable);

    List<GovernanceAuditEventDocument> findByAdvisoryEventIdOrderByCreatedAtAsc(String advisoryEventId);

    long countByAdvisoryEventId(String advisoryEventId);
}
