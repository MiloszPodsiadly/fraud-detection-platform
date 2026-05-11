package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface FraudCaseLifecycleIdempotencyRepository extends MongoRepository<FraudCaseLifecycleIdempotencyRecordDocument, String> {
    Optional<FraudCaseLifecycleIdempotencyRecordDocument> findByIdempotencyKeyHash(String idempotencyKeyHash);
}
