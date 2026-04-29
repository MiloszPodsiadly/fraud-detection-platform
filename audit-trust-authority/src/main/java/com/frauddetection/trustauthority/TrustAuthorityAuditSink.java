package com.frauddetection.trustauthority;

public interface TrustAuthorityAuditSink {

    void append(TrustAuthorityAuditEvent event);

    TrustAuthorityAuditIntegrityResponse integrity(int limit);

    TrustAuthorityAuditHeadResponse head();
}
