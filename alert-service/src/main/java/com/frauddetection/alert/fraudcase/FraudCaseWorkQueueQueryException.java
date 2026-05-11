package com.frauddetection.alert.fraudcase;

public class FraudCaseWorkQueueQueryException extends RuntimeException {

    private final String code;

    public FraudCaseWorkQueueQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
