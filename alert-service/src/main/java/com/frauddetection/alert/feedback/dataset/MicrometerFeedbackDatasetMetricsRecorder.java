package com.frauddetection.alert.feedback.dataset;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class MicrometerFeedbackDatasetMetricsRecorder implements FeedbackDatasetMetricsRecorder {

    static final String BUILD_METRIC = "fraud.feedback.dataset.build";
    static final String EVIDENCE_OUTCOME_METRIC = "fraud.feedback.dataset.ml_prediction_evidence";

    private static final Logger log = LoggerFactory.getLogger(MicrometerFeedbackDatasetMetricsRecorder.class);

    private final MeterRegistry meterRegistry;

    public MicrometerFeedbackDatasetMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
    }

    @Override
    public void record(FeedbackDatasetBuildResult result) {
        try {
            Counter.builder(BUILD_METRIC)
                    .tag("result", result.failed() ? result.failureReason().name() : "SUCCESS")
                    .register(meterRegistry)
                    .increment();
            if (!result.failed()) {
                result.records().forEach(record -> Counter.builder(EVIDENCE_OUTCOME_METRIC)
                        .tag("status", record.mlPredictionEvidenceStatus().name())
                        .register(meterRegistry)
                        .increment());
            }
        } catch (RuntimeException exception) {
            log.warn("Feedback dataset metric recording failed");
        }
    }
}
