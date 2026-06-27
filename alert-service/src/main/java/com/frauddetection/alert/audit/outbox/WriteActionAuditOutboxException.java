package com.frauddetection.alert.audit.outbox;

public class WriteActionAuditOutboxException extends RuntimeException {

    public WriteActionAuditOutboxException(String reason) {
        super(reason);
    }

    public WriteActionAuditOutboxException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
