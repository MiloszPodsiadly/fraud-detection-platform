package com.frauddetection.alert.outbox;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionalOutboxRecordRepository extends MongoRepository<TransactionalOutboxRecordDocument, String> {

    long countByStatus(TransactionalOutboxStatus status);

    long countByStatusIn(Collection<TransactionalOutboxStatus> statuses);

    Optional<TransactionalOutboxRecordDocument> findByMutationCommandId(String mutationCommandId);

    Optional<TransactionalOutboxRecordDocument> findTopByStatusInOrderByCreatedAtAsc(Collection<TransactionalOutboxStatus> statuses);

    List<TransactionalOutboxRecordDocument> findTop100ByStatusOrderByCreatedAtAsc(TransactionalOutboxStatus status);

    List<TransactionalOutboxRecordDocument> findTop100ByStatusInOrderByCreatedAtAsc(Collection<TransactionalOutboxStatus> statuses);

    List<TransactionalOutboxRecordDocument> findTop100ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(
            TransactionalOutboxStatus status,
            Instant leaseExpiresAt
    );
}
