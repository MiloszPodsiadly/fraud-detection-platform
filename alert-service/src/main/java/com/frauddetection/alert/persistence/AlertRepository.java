package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AlertRepository extends MongoRepository<AlertDocument, String> {

    boolean existsByTransactionId(String transactionId);
}
