package com.frauddetection.alert.observability;

public interface FraudCaseReadModelMetrics {

    void recordEvidenceSummary(FraudCaseReadModelOutcome outcome);

    void recordEvidenceTimeline(FraudCaseReadModelOutcome outcome);
}
