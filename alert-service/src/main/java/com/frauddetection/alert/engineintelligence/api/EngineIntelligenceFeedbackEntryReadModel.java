package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;

import java.time.Instant;
import java.util.List;

public record EngineIntelligenceFeedbackEntryReadModel(
        String feedbackId,
        boolean engineIntelligenceAvailable,
        EngineIntelligenceFeedbackType feedbackType,
        EngineIntelligenceFeedbackUsefulness usefulness,
        EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
        List<String> selectedReasonCodes,
        Instant submittedAt
) {
    public EngineIntelligenceFeedbackEntryReadModel {
        selectedReasonCodes = selectedReasonCodes == null ? List.of() : List.copyOf(selectedReasonCodes);
    }
}
