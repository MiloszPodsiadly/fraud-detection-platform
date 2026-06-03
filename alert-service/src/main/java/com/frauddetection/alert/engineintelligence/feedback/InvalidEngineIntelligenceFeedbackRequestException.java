package com.frauddetection.alert.engineintelligence.feedback;

import java.util.List;

public class InvalidEngineIntelligenceFeedbackRequestException extends RuntimeException {

    private final List<String> details;

    public InvalidEngineIntelligenceFeedbackRequestException(List<String> details) {
        super("Invalid engine intelligence feedback request.");
        this.details = List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
