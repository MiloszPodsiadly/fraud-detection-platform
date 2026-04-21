package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ScoredTransactionRepository extends MongoRepository<ScoredTransactionDocument, String> {
}
