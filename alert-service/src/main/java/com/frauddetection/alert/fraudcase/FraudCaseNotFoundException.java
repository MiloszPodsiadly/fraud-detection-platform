package com.frauddetection.alert.fraudcase;

public class FraudCaseNotFoundException extends RuntimeException {

    public FraudCaseNotFoundException(String caseId) {
        super("Fraud case not found: " + caseId);
    }
}
