package com.frauddetection.alert.engineintelligence.api;

import java.util.List;

public record EngineIntelligenceFeedbackReadModel(
        String transactionId,
        List<EngineIntelligenceFeedbackEntryReadModel> feedback,
        EngineIntelligenceFeedbackPage page
) {
    public EngineIntelligenceFeedbackReadModel {
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }
}
