package com.frauddetection.alert.audit.external;

import org.springframework.http.HttpStatus;

import java.util.List;

public class AuditEvidenceExportRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final String reasonCode;
    private final List<String> details;

    public AuditEvidenceExportRejectedException(HttpStatus status, String reasonCode, String message, List<String> details) {
        super(message);
        this.status = status;
        this.reasonCode = reasonCode;
        this.details = List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public List<String> details() {
        return details;
    }
}
