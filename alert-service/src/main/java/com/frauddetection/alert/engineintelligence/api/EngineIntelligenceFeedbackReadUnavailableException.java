package com.frauddetection.alert.engineintelligence.api;

public class EngineIntelligenceFeedbackReadUnavailableException extends RuntimeException {

    public EngineIntelligenceFeedbackReadUnavailableException() {
        super("Engine intelligence feedback is temporarily unavailable.");
    }
}
