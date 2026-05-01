package com.frauddetection.alert.regulated;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RegulatedMutationCommandRepository extends MongoRepository<RegulatedMutationCommandDocument, String> {
    Optional<RegulatedMutationCommandDocument> findByIdempotencyKey(String idempotencyKey);
}
