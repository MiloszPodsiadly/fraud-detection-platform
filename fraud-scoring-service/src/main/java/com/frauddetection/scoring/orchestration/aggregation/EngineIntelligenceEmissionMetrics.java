package com.frauddetection.scoring.orchestration.aggregation;

import java.time.Duration;

public interface EngineIntelligenceEmissionMetrics {

    void recordSkippedDisabled();

    void recordAttempt();

    void recordSuccess();

    void recordOmitted(EngineIntelligenceEmissionOmissionReason reason);

    void recordLatency(Duration latency);
}
