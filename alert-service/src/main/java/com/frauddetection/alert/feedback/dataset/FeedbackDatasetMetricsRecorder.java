package com.frauddetection.alert.feedback.dataset;

public interface FeedbackDatasetMetricsRecorder {

    void record(FeedbackDatasetBuildResult result);

    static FeedbackDatasetMetricsRecorder noOp() {
        return result -> { };
    }
}
