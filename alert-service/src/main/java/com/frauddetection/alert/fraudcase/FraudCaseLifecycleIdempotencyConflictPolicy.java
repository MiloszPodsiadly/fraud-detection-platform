package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.idempotency.SharedIdempotencyClaim;
import com.frauddetection.alert.idempotency.SharedIdempotencyConflictPolicy;
import com.frauddetection.alert.persistence.FraudCaseLifecycleIdempotencyRecordDocument;
import org.springframework.stereotype.Service;

@Service
public class FraudCaseLifecycleIdempotencyConflictPolicy {

    private final SharedIdempotencyConflictPolicy sharedConflictPolicy;

    public FraudCaseLifecycleIdempotencyConflictPolicy(SharedIdempotencyConflictPolicy sharedConflictPolicy) {
        this.sharedConflictPolicy = sharedConflictPolicy;
    }

    public void validateSameOperation(
            FraudCaseLifecycleIdempotencyRecordDocument existing,
            FraudCaseLifecycleIdempotencyCommand command
    ) {
        SharedIdempotencyClaim existingClaim = new SharedIdempotencyClaim(
                existing.getRequestHash(),
                existing.getAction(),
                existing.getActorId(),
                existing.getCaseIdScope()
        );
        SharedIdempotencyClaim candidateClaim = new SharedIdempotencyClaim(
                command.requestHash(),
                command.action(),
                command.actorId(),
                command.caseIdScope()
        );
        if (!sharedConflictPolicy.sameClaim(existingClaim, candidateClaim)) {
            throw new FraudCaseIdempotencyConflictException();
        }
    }
}
