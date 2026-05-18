package com.frauddetection.alert.suspicious.api;

public class SuspiciousTransactionReadValidationException extends RuntimeException {

    private final String code;

    public SuspiciousTransactionReadValidationException(String code) {
        super("Invalid suspicious transaction read request.");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
