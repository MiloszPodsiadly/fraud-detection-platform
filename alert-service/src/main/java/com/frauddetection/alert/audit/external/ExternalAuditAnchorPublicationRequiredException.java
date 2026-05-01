package com.frauddetection.alert.audit.external;

public class ExternalAuditAnchorPublicationRequiredException extends RuntimeException {

    private final String reason;

    public ExternalAuditAnchorPublicationRequiredException(String reason) {
        super("External audit anchor publication is required but failed.");
        this.reason = reason == null ? "UNKNOWN" : reason;
    }

    public String reason() {
        return reason;
    }
}
