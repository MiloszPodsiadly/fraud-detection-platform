package com.frauddetection.alert.engineintelligence.api;

public class EngineIntelligenceScoredTransactionNotFoundException extends RuntimeException {

    public EngineIntelligenceScoredTransactionNotFoundException() {
        super("Scored transaction not found.");
    }
}
