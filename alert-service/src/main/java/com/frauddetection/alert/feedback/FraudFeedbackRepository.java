package com.frauddetection.alert.feedback;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FraudFeedbackRepository extends MongoRepository<FraudFeedbackRecord, String> {

    Optional<FraudFeedbackRecord> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);
}
