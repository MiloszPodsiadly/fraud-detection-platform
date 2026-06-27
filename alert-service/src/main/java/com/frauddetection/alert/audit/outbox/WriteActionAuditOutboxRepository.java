package com.frauddetection.alert.audit.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WriteActionAuditOutboxRepository extends MongoRepository<WriteActionAuditOutboxRecord, String> {

    Optional<WriteActionAuditOutboxRecord> findByIdempotencyKey(String idempotencyKey);

    @Query(value = "{ 'status': { $in: ?0 }, '$or': [ { 'next_attempt_at': null }, { 'next_attempt_at': { $lte: ?1 } } ] }")
    List<WriteActionAuditOutboxRecord> findPublishable(
            Collection<WriteActionAuditOutboxStatus> statuses,
            Instant now,
            Pageable pageable
    );
}
