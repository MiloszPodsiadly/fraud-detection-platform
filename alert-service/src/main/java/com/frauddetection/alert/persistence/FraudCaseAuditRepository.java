package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FraudCaseAuditRepository extends MongoRepository<FraudCaseAuditEntryDocument, String> {

    List<FraudCaseAuditEntryDocument> findByCaseIdOrderByOccurredAtAsc(String caseId);
}
