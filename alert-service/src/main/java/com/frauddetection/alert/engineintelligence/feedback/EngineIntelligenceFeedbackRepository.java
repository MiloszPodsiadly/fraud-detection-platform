package com.frauddetection.alert.engineintelligence.feedback;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EngineIntelligenceFeedbackRepository extends MongoRepository<EngineIntelligenceFeedbackDocument, String> {

    Optional<EngineIntelligenceFeedbackDocument> findBySubmittedByAndTransactionIdAndIdempotencyKeyHash(
            String submittedBy,
            String transactionId,
            String idempotencyKeyHash
    );

    List<EngineIntelligenceFeedbackDocument> findByTransactionId(String transactionId, Pageable pageable);
}
