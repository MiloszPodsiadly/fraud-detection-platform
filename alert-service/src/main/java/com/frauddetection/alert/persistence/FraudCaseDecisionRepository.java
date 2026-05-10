package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FraudCaseDecisionRepository extends MongoRepository<FraudCaseDecisionDocument, String> {

    List<FraudCaseDecisionDocument> findByCaseIdOrderByCreatedAtAsc(String caseId);
}
