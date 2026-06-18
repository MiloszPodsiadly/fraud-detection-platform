package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ScoredTransactionRepository extends MongoRepository<ScoredTransactionDocument, String> {

    Optional<ScoredTransactionDocument> findByTransactionId(String transactionId);
}
