package com.frauddetection.alert.idempotency;

public record SharedIdempotencyClaim(
        String requestHash,
        String action,
        String actorId,
        String scope
) {
}
