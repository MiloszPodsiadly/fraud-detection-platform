package com.frauddetection.alert.audit;

public class AuditPersistenceUnavailableException extends RuntimeException {

    public AuditPersistenceUnavailableException() {
    }

    public AuditPersistenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
