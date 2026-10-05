package com.frauddetection.alert.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.Optional;

public interface AlertRepository extends MongoRepository<AlertDocument, String> {

    Optional<AlertDocument> findByTransactionId(String transactionId);

    long countByDecisionOutboxStatus(String decisionOutboxStatus);

    long countByDecisionOutboxResolutionPending(boolean decisionOutboxResolutionPending);

    long countByDecisionOutboxStatusIn(Collection<String> decisionOutboxStatuses);

    Optional<AlertDocument> findTopByDecisionOutboxStatusOrderByDecidedAtAsc(String decisionOutboxStatus);

    Optional<AlertDocument> findTopByDecisionOutboxStatusInOrderByDecidedAtAsc(Collection<String> decisionOutboxStatuses);

}
