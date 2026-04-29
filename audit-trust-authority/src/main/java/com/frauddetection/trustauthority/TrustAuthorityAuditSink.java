package com.frauddetection.trustauthority;

public interface TrustAuthorityAuditSink {

    void append(TrustAuthorityAuditEvent event);
}
