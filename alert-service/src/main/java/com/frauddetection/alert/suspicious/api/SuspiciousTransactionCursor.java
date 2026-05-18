package com.frauddetection.alert.suspicious.api;

import java.time.Instant;

public record SuspiciousTransactionCursor(
        Instant detectedAt,
        String suspiciousTransactionId
) {
}
