package com.frauddetection.alert.audit.external;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.audit.evidence-export.rate-limiter", havingValue = "stub-distributed")
public class StubDistributedAuditEvidenceExportRateLimiter implements AuditEvidenceExportRateLimiterStrategy {

    @Override
    public boolean allow(String actorId) {
        return true;
    }
}
