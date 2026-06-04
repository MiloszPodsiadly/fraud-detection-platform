package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackAccuracyAssessment;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackDocument;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackType;
import com.frauddetection.alert.engineintelligence.feedback.EngineIntelligenceFeedbackUsefulness;

import java.time.Instant;
import java.util.List;

public record EngineIntelligenceFeedbackResponse(
        String feedbackId,
        String transactionId,
        boolean engineIntelligenceAvailable,
        EngineIntelligenceFeedbackType feedbackType,
        EngineIntelligenceFeedbackUsefulness usefulness,
        EngineIntelligenceFeedbackAccuracyAssessment accuracyAssessment,
        List<String> selectedReasonCodes,
        Instant submittedAt,
        String operationStatus
) {
    public EngineIntelligenceFeedbackResponse {
        selectedReasonCodes = selectedReasonCodes == null ? List.of() : List.copyOf(selectedReasonCodes);
    }

    public static EngineIntelligenceFeedbackResponse created(EngineIntelligenceFeedbackDocument document) {
        return from(document, "CREATED");
    }

    public static EngineIntelligenceFeedbackResponse existing(EngineIntelligenceFeedbackDocument document) {
        return from(document, "EXISTING");
    }

    private static EngineIntelligenceFeedbackResponse from(EngineIntelligenceFeedbackDocument document, String operationStatus) {
        return new EngineIntelligenceFeedbackResponse(
                document.getFeedbackId(),
                document.getTransactionId(),
                document.isEngineIntelligenceAvailable(),
                document.getFeedbackType(),
                document.getUsefulness(),
                document.getAccuracyAssessment(),
                document.getSelectedReasonCodes(),
                document.getSubmittedAt(),
                operationStatus
        );
    }
}
