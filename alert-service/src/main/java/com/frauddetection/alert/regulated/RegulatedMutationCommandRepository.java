package com.frauddetection.alert.regulated;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RegulatedMutationCommandRepository extends MongoRepository<RegulatedMutationCommandDocument, String> {
    Optional<RegulatedMutationCommandDocument> findByIdempotencyKey(String idempotencyKey);

    Optional<RegulatedMutationCommandDocument> findByIdempotencyKeyHash(String idempotencyKeyHash);

    List<RegulatedMutationCommandDocument> findTop100ByExecutionStatusInAndUpdatedAtBefore(
            Collection<RegulatedMutationExecutionStatus> executionStatuses,
            Instant updatedAt
    );

    List<RegulatedMutationCommandDocument> findTop100ByStateInAndUpdatedAtBefore(
            Collection<RegulatedMutationState> states,
            Instant updatedAt
    );

    long countByExecutionStatus(RegulatedMutationExecutionStatus executionStatus);

    long countByExecutionStatusAndLeaseExpiresAtBefore(RegulatedMutationExecutionStatus executionStatus, Instant leaseExpiresAt);

    long countByExecutionStatusAndAttemptCountGreaterThanEqual(
            RegulatedMutationExecutionStatus executionStatus,
            int attemptCount
    );

    long countByState(RegulatedMutationState state);

    java.util.Optional<RegulatedMutationCommandDocument> findTopByExecutionStatusOrderByUpdatedAtAsc(
            RegulatedMutationExecutionStatus executionStatus
    );

    List<RegulatedMutationCommandDocument> findTop100ByExecutionStatusOrderByUpdatedAtAsc(
            RegulatedMutationExecutionStatus executionStatus
    );

    List<RegulatedMutationCommandDocument> findTop100ByExecutionStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(
            RegulatedMutationExecutionStatus executionStatus,
            Instant leaseExpiresAt
    );
}
