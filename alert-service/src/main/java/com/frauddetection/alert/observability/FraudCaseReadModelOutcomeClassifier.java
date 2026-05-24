package com.frauddetection.alert.observability;

import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;

public final class FraudCaseReadModelOutcomeClassifier {

    private FraudCaseReadModelOutcomeClassifier() {
    }

    public static FraudCaseReadModelOutcome classifySummary(FraudCaseEvidenceSummaryResponse response) {
        if (response.truncated()) {
            return FraudCaseReadModelOutcome.TRUNCATED;
        }
        if (response.legacy()) {
            return FraudCaseReadModelOutcome.LEGACY;
        }
        if (response.partial()) {
            return FraudCaseReadModelOutcome.PARTIAL;
        }
        if (response.evidenceItemCount() <= 0) {
            return FraudCaseReadModelOutcome.EMPTY;
        }
        return FraudCaseReadModelOutcome.AVAILABLE;
    }

    public static FraudCaseReadModelOutcome classifyTimeline(FraudCaseEvidenceTimelineResponse response) {
        if (response.truncated()) {
            return FraudCaseReadModelOutcome.TRUNCATED;
        }
        if (response.legacy()) {
            return FraudCaseReadModelOutcome.LEGACY;
        }
        if (response.partial()) {
            return FraudCaseReadModelOutcome.PARTIAL;
        }
        if (response.events().isEmpty()) {
            return FraudCaseReadModelOutcome.EMPTY;
        }
        return FraudCaseReadModelOutcome.AVAILABLE;
    }
}
