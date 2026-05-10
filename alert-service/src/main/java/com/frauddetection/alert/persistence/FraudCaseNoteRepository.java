package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FraudCaseNoteRepository extends MongoRepository<FraudCaseNoteDocument, String> {

    List<FraudCaseNoteDocument> findByCaseIdOrderByCreatedAtAsc(String caseId);
}
