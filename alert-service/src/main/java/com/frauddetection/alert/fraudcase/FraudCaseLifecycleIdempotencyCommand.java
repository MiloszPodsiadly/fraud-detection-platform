package com.frauddetection.alert.fraudcase;

import java.time.Instant;

public record FraudCaseLifecycleIdempotencyCommand(
        String idempotencyKey,
        String action,
        String actorId,
        String caseIdScope,
        String requestHash,
        Instant now
) {
}
