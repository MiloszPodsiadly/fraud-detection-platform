package com.frauddetection.alert.engineintelligence.api;

import com.frauddetection.alert.engineintelligence.observability.EngineIntelligenceFeedbackReadMetricReason;

public class EngineIntelligenceFeedbackReadUnavailableException extends RuntimeException {

    private final EngineIntelligenceFeedbackReadMetricReason metricReason;

    public EngineIntelligenceFeedbackReadUnavailableException() {
        this(EngineIntelligenceFeedbackReadMetricReason.STORE_UNAVAILABLE);
    }

    public EngineIntelligenceFeedbackReadUnavailableException(EngineIntelligenceFeedbackReadMetricReason metricReason) {
        super("Engine intelligence feedback is temporarily unavailable.");
        this.metricReason = metricReason == null
                ? EngineIntelligenceFeedbackReadMetricReason.UNKNOWN_FAILURE
                : metricReason;
    }

    public EngineIntelligenceFeedbackReadMetricReason metricReason() {
        return metricReason;
    }
}
