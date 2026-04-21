package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FraudCaseRepository extends MongoRepository<FraudCaseDocument, String> {

    Optional<FraudCaseDocument> findByCaseKey(String caseKey);
}
