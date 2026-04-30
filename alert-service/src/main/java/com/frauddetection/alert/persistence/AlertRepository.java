package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends MongoRepository<AlertDocument, String> {

    boolean existsByTransactionId(String transactionId);

    List<AlertDocument> findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(String decisionOutboxStatus);

    long countByDecisionOutboxStatusIn(Collection<String> decisionOutboxStatuses);

    Optional<AlertDocument> findTopByDecisionOutboxStatusInOrderByDecidedAtAsc(Collection<String> decisionOutboxStatuses);
}
