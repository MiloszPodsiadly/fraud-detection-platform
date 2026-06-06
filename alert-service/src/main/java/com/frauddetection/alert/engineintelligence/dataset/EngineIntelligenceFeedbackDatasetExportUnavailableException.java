package com.frauddetection.alert.engineintelligence.dataset;

public class EngineIntelligenceFeedbackDatasetExportUnavailableException extends RuntimeException {

    private final EngineIntelligenceFeedbackDatasetExportFailureReason reason;

    public EngineIntelligenceFeedbackDatasetExportUnavailableException(
            EngineIntelligenceFeedbackDatasetExportFailureReason reason
    ) {
        super("Engine intelligence feedback dataset export is temporarily unavailable.");
        this.reason = reason == null
                ? EngineIntelligenceFeedbackDatasetExportFailureReason.CORRUPTED_SOURCE_DATA
                : reason;
    }

    public EngineIntelligenceFeedbackDatasetExportFailureReason reason() {
        return reason;
    }
}
