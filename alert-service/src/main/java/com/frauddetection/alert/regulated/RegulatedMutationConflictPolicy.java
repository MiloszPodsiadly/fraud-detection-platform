package com.frauddetection.alert.regulated;

import com.frauddetection.alert.idempotency.SharedIdempotencyClaim;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.springframework.stereotype.Service;

@Service
public class RegulatedMutationConflictPolicy {

    private final SharedIdempotencyConflictPolicy sharedConflictPolicy;

    public RegulatedMutationConflictPolicy() {
        this(new SharedIdempotencyConflictPolicy());
    }

    public RegulatedMutationConflictPolicy(SharedIdempotencyConflictPolicy sharedConflictPolicy) {
        this.sharedConflictPolicy = sharedConflictPolicy;
    }

    public <R, S> RegulatedMutationCommandDocument existingOrConflict(
            RegulatedMutationCommandDocument existing,
            RegulatedMutationCommand<R, S> command
    ) {
        if (existing == null) {
            throw new IllegalArgumentException("existing regulated mutation command is required");
        }
        if (command == null) {
            throw new IllegalArgumentException("regulated mutation command is required");
        }
        String existingActor = existing.getIntentActorId() == null ? command.actorId() : existing.getIntentActorId();
        SharedIdempotencyClaim existingClaim = new SharedIdempotencyClaim(
                existing.getRequestHash(),
                command.action().name(),
                existingActor,
                command.resourceId()
        );
        SharedIdempotencyClaim candidateClaim = new SharedIdempotencyClaim(
                command.requestHash(),
                command.action().name(),
                command.actorId(),
                command.resourceId()
        );
        if (!sharedConflictPolicy.sameClaim(existingClaim, candidateClaim)) {
            throw new ConflictingIdempotencyKeyException();
        }
        return existing;
    }
}
