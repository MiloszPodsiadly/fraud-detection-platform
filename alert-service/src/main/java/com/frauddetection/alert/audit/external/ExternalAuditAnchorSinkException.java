package com.frauddetection.alert.audit.external;

public class ExternalAuditAnchorSinkException extends RuntimeException {

    private final String reason;

    public ExternalAuditAnchorSinkException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
