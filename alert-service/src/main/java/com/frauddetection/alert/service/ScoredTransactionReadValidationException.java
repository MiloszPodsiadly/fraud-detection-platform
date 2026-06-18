package com.frauddetection.alert.service;

public class ScoredTransactionReadValidationException extends RuntimeException {

    private final String code;

    public ScoredTransactionReadValidationException(String code) {
        super("Invalid scored transaction read request.");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
