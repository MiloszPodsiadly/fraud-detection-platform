package com.frauddetection.alert.idempotency;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class SharedIdempotencyConflictPolicy {

    public boolean sameClaim(SharedIdempotencyClaim existing, SharedIdempotencyClaim candidate) {
        if (existing == null || candidate == null) {
            throw new IllegalArgumentException("idempotency claims are required");
        }
        return Objects.equals(existing.requestHash(), candidate.requestHash())
                && Objects.equals(existing.action(), candidate.action())
                && Objects.equals(existing.actorId(), candidate.actorId())
                && Objects.equals(existing.scope(), candidate.scope());
    }
}
