package com.frauddetection.alert.audit;

import java.time.Instant;

record AuditIntegrityQuery(
        Instant from,
        Instant to,
        String sourceService,
        AuditIntegrityVerificationMode mode,
        int limit
) {
}
