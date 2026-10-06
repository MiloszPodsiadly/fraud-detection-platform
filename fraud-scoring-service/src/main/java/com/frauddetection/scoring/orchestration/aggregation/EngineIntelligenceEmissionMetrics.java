package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

import java.time.Duration;

public interface EngineIntelligenceEmissionMetrics {

    void recordSkippedDisabled();

    void recordAttempt();

    void recordSuccess();

    void recordOmitted(EngineIntelligenceEmissionOmissionReason reason);

    void recordEvidenceOmitted(MlPredictionEvidenceOmissionReason reason);

    void recordLatency(Duration latency);
}
