package com.frauddetection.alert.regulated;

import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class RegulatedMutationConflictPolicy {

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
        if (!Objects.equals(existing.getRequestHash(), command.requestHash())
                || (existing.getIntentActorId() != null && !existing.getIntentActorId().equals(command.actorId()))) {
            throw new ConflictingIdempotencyKeyException();
        }
        return existing;
    }
}
