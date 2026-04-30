package com.frauddetection.alert.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AuditDegradationEventRepository extends MongoRepository<AuditDegradationEventDocument, String> {

    long countByTypeAndResolved(String type, boolean resolved);

    long countByResolved(boolean resolved);

    Optional<AuditDegradationEventDocument> findByAuditId(String auditId);

    List<AuditDegradationEventDocument> findTop100ByResolvedOrderByTimestampAsc(boolean resolved);
}
