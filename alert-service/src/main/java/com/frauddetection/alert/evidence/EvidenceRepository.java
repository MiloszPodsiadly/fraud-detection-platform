package com.frauddetection.alert.evidence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EvidenceRepository extends MongoRepository<EvidenceDocument, String> {
    Page<EvidenceDocument> findByTransactionId(String transactionId, Pageable pageable);

    Page<EvidenceDocument> findByAlertId(String alertId, Pageable pageable);

    Page<EvidenceDocument> findByCorrelationId(String correlationId, Pageable pageable);
}
