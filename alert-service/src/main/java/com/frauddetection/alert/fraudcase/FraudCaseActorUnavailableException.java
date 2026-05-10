package com.frauddetection.alert.fraudcase;

public class FraudCaseActorUnavailableException extends RuntimeException {

    public FraudCaseActorUnavailableException() {
        super("Authenticated or explicit actor is required for fraud case mutation.");
    }
}
