package com.frauddetection.alert.audit.trust;

public class AuditTrustAttestationException extends RuntimeException {

    public AuditTrustAttestationException(String message) {
        super(message);
    }

    public AuditTrustAttestationException(String message, Throwable cause) {
        super(message, cause);
    }
}
