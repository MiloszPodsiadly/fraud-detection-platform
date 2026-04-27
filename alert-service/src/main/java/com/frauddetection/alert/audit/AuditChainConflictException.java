package com.frauddetection.alert.audit;

public class AuditChainConflictException extends RuntimeException {

    AuditChainConflictException(String message) {
        super(message);
    }
}
