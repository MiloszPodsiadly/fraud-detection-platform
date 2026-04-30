package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AlertRepository extends MongoRepository<AlertDocument, String> {

    boolean existsByTransactionId(String transactionId);

    List<AlertDocument> findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(String decisionOutboxStatus);
}
