package com.frauddetection.alert.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditDegradationEventRepository extends MongoRepository<AuditDegradationEventDocument, String> {

    long countByTypeAndResolved(String type, boolean resolved);
}
