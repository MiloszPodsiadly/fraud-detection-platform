package com.frauddetection.alert.audit;

public class AuditImmutableMutationException extends RuntimeException {

    AuditImmutableMutationException(String operation) {
        super("Durable audit events are append-only; " + operation + " is not allowed.");
    }
}
