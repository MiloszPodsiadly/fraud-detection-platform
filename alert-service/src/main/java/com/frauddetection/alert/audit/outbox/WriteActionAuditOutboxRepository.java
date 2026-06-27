package com.frauddetection.alert.audit.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WriteActionAuditOutboxRepository extends MongoRepository<WriteActionAuditOutboxRecord, String> {

    Optional<WriteActionAuditOutboxRecord> findByIdempotencyKey(String idempotencyKey);

    @Query(value = "{ '$or': [ { 'status': { $in: ['PENDING', 'FAILED_RETRYABLE'] }, '$or': [ { 'next_attempt_at': null }, { 'next_attempt_at': { $lte: ?0 } } ] }, { 'status': 'PUBLISHING', 'claim_expires_at': { $lte: ?0 } } ] }")
    List<WriteActionAuditOutboxRecord> findPublishable(
            Instant now,
            Pageable pageable
    );
}
