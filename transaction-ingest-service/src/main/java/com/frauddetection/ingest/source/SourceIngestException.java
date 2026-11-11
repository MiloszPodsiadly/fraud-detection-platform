package com.frauddetection.ingest.source;

import org.springframework.http.HttpStatus;

public final class SourceIngestException extends RuntimeException {
    private final HttpStatus status;
    public SourceIngestException(HttpStatus status, String code) {
        super(code);
        if (status == null || code == null || !code.matches("[A-Z0-9_]{1,96}")) {
            throw new IllegalArgumentException("SOURCE_INGEST_ERROR_INVALID");
        }
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
