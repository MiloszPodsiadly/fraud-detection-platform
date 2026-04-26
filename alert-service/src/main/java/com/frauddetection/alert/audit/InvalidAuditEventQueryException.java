package com.frauddetection.alert.audit;

import java.util.List;

public class InvalidAuditEventQueryException extends RuntimeException {

    private final List<String> details;

    public InvalidAuditEventQueryException(List<String> details) {
        super("Invalid audit event query.");
        this.details = List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
