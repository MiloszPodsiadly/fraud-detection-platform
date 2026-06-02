package com.frauddetection.alert.engineintelligence.api;

public class EngineIntelligenceProjectionReadUnavailableException extends RuntimeException {

    public EngineIntelligenceProjectionReadUnavailableException() {
        super("Engine intelligence projection is temporarily unavailable.");
    }
}
