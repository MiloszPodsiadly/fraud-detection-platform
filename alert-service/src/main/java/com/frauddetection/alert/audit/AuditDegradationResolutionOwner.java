package com.frauddetection.alert.audit;

enum AuditDegradationResolutionOwner {
    MANUAL_ADMINISTRATION,
    VERIFIED_EXTERNAL_HEAD_RECOVERY;

    static AuditDegradationResolutionOwner forIncidentType(String incidentType) {
        if (AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY.equals(incidentType)) {
            return VERIFIED_EXTERNAL_HEAD_RECOVERY;
        }
        if (AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED.equals(incidentType)) {
            return MANUAL_ADMINISTRATION;
        }
        throw new IllegalStateException("AUDIT_DEGRADATION_RESOLUTION_OWNER_UNDEFINED");
    }
}
