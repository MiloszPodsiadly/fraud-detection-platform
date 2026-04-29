package com.frauddetection.trustauthority;

public interface TrustAuthorityAuditSink {

    void append(TrustAuthorityAuditEvent event);

    TrustAuthorityAuditIntegrityResponse integrity(int limit);

    default TrustAuthorityAuditIntegrityResponse integrity(int limit, String mode) {
        return integrity(limit);
    }

    TrustAuthorityAuditHeadResponse head();

    default boolean requestSeen(String callerService, String requestId) {
        return false;
    }
}
