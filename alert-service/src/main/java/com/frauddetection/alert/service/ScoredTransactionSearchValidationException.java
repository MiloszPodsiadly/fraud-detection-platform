package com.frauddetection.alert.service;

public class ScoredTransactionSearchValidationException extends RuntimeException {

    private final String code;

    public ScoredTransactionSearchValidationException(String code) {
        super("Invalid scored transaction search request.");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
