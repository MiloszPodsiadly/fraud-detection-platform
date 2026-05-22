package com.frauddetection.alert.suspicious.api.observability;

public interface LinkedAlertContextMetricsRecorder {

    void record(LinkedAlertContextMetricOutcome outcome);
}
