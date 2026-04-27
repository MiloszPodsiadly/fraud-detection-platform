package com.frauddetection.alert.audit.external;

public interface AuditEvidenceExportRateLimiterStrategy {

    boolean allow(String actorId);
}
