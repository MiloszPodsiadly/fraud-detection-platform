package com.frauddetection.alert.observability;

import com.frauddetection.alert.api.FraudCaseEvidenceSummaryResponse;
import com.frauddetection.alert.api.FraudCaseEvidenceTimelineResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventResponse;
import com.frauddetection.alert.api.FraudCaseTimelineEventType;
import com.frauddetection.alert.api.FraudCaseTimelineLinkedEntityType;
import com.frauddetection.alert.evidence.EvidenceSource;
import com.frauddetection.alert.evidence.EvidenceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCaseReadModelOutcomeClassifierTest {

    @Test
    void evidenceSummaryOutcomePrecedenceIsBoundedAndExplicit() {
        assertThat(FraudCaseReadModelOutcomeClassifier.classifySummary(summary(true, true, true, 1)))
                .isEqualTo(FraudCaseReadModelOutcome.TRUNCATED);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifySummary(summary(false, true, true, 1)))
                .isEqualTo(FraudCaseReadModelOutcome.LEGACY);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifySummary(summary(false, false, true, 1)))
                .isEqualTo(FraudCaseReadModelOutcome.PARTIAL);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifySummary(summary(false, false, false, 0)))
                .isEqualTo(FraudCaseReadModelOutcome.EMPTY);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifySummary(summary(false, false, false, 1)))
                .isEqualTo(FraudCaseReadModelOutcome.AVAILABLE);
    }

    @Test
    void evidenceTimelineOutcomePrecedenceIsBoundedAndExplicit() {
        assertThat(FraudCaseReadModelOutcomeClassifier.classifyTimeline(timeline(true, true, true, true)))
                .isEqualTo(FraudCaseReadModelOutcome.TRUNCATED);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifyTimeline(timeline(false, true, true, true)))
                .isEqualTo(FraudCaseReadModelOutcome.LEGACY);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifyTimeline(timeline(false, false, true, true)))
                .isEqualTo(FraudCaseReadModelOutcome.PARTIAL);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifyTimeline(timeline(false, false, false, false)))
                .isEqualTo(FraudCaseReadModelOutcome.EMPTY);
        assertThat(FraudCaseReadModelOutcomeClassifier.classifyTimeline(timeline(false, false, false, true)))
                .isEqualTo(FraudCaseReadModelOutcome.AVAILABLE);
    }

    private FraudCaseEvidenceSummaryResponse summary(boolean truncated, boolean legacy, boolean partial, int evidenceItemCount) {
        return new FraudCaseEvidenceSummaryResponse(
                "case-1",
                EvidenceStatus.AVAILABLE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1,
                evidenceItemCount,
                partial,
                legacy,
                truncated,
                null,
                Instant.parse("2026-05-24T10:00:00Z")
        );
    }

    private FraudCaseEvidenceTimelineResponse timeline(boolean truncated, boolean legacy, boolean partial, boolean hasEvents) {
        return new FraudCaseEvidenceTimelineResponse(
                "case-1",
                hasEvents ? List.of(event()) : List.of(),
                partial,
                legacy,
                truncated,
                null,
                Instant.parse("2026-05-24T10:00:00Z")
        );
    }

    private FraudCaseTimelineEventResponse event() {
        return new FraudCaseTimelineEventResponse(
                "FRAUD_CASE_CREATED_000001",
                FraudCaseTimelineEventType.FRAUD_CASE_CREATED,
                Instant.parse("2026-05-24T10:00:00Z"),
                EvidenceSource.ALERT_SERVICE,
                EvidenceStatus.AVAILABLE,
                "Fraud case created",
                "Read-only timeline event derived from existing fraud-case read data.",
                FraudCaseTimelineLinkedEntityType.FRAUD_CASE,
                false
        );
    }
}
