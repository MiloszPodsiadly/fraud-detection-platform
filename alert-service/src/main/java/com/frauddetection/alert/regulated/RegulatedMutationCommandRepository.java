package com.frauddetection.alert.regulated;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RegulatedMutationCommandRepository extends MongoRepository<RegulatedMutationCommandDocument, String> {
    Optional<RegulatedMutationCommandDocument> findByIdempotencyKey(String idempotencyKey);

    List<RegulatedMutationCommandDocument> findTop100ByExecutionStatusInAndUpdatedAtBefore(
            Collection<RegulatedMutationExecutionStatus> executionStatuses,
            Instant updatedAt
    );

    List<RegulatedMutationCommandDocument> findTop100ByStateInAndUpdatedAtBefore(
            Collection<RegulatedMutationState> states,
            Instant updatedAt
    );
}
