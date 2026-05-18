package com.frauddetection.alert.evidence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface EvidenceRepository extends MongoRepository<EvidenceDocument, String> {
    List<EvidenceDocument> findByTransactionId(String transactionId);
    List<EvidenceDocument> findByAlertId(String alertId);
    List<EvidenceDocument> findByCorrelationId(String correlationId);
}
