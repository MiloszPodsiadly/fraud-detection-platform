package com.frauddetection.alert.service;

public class ScoredTransactionNotFoundException extends RuntimeException {

    public ScoredTransactionNotFoundException() {
        super("Scored transaction not found.");
    }
}
