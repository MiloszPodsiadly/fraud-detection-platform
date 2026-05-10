package com.frauddetection.alert.api;

import java.time.Instant;

public record FraudCaseNoteResponse(
        String id,
        String caseId,
        String body,
        String createdBy,
        Instant createdAt,
        boolean internalOnly
) {
}
