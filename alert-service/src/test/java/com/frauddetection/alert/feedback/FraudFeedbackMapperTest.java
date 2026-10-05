package com.frauddetection.alert.feedback;

import com.frauddetection.alert.api.EngineIntelligenceResponseStatus;
import com.frauddetection.alert.domain.ScoringOccurrenceOwnership;
import com.frauddetection.common.events.intelligence.EngineIntelligenceAgreementStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceComparisonType;
import com.frauddetection.common.events.intelligence.EngineIntelligenceRiskMismatchStatus;
import com.frauddetection.common.events.intelligence.EngineIntelligenceScoreDeltaBucket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudFeedbackMapperTest {

    private final FraudFeedbackMapper mapper = new FraudFeedbackMapper();

    @Test
    void downgradesAvailableEngineIntelligenceWhenComparisonSnapshotIsCorrupted() {
        FraudFeedbackRecord record = recordWithCorruptedComparisonSnapshot(EngineIntelligenceResponseStatus.AVAILABLE);

        FraudFeedbackResponse response = mapper.toResponse(record);

        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.comparisonType()).isNull();
        assertThat(response.comparedEngineIds()).isEmpty();
        assertThat(response.agreementStatus()).isNull();
        assertThat(response.riskMismatchStatus()).isNull();
        assertThat(response.scoreDeltaBucket()).isNull();
    }

    @Test
    void keepsUnavailableEngineIntelligenceWhenComparisonSnapshotIsCorrupted() {
        FraudFeedbackRecord record = recordWithCorruptedComparisonSnapshot(EngineIntelligenceResponseStatus.UNAVAILABLE);

        FraudFeedbackResponse response = mapper.toResponse(record);

        assertThat(response.engineIntelligenceStatus()).isEqualTo(EngineIntelligenceResponseStatus.UNAVAILABLE);
        assertThat(response.comparisonType()).isNull();
    }

    @Test
    void authoritativeOccurrenceIdentityIsImmutableInsideFeedbackRecord() {
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        ScoringOccurrenceOwnership occurrence = ScoringOccurrenceOwnership.authoritative(
                "event-a",
                Instant.parse("2026-10-05T08:00:00Z"),
                "a".repeat(64)
        );
        record.captureScoringOccurrence(occurrence);
        record.captureScoringOccurrence(occurrence);

        assertThat(record.scoringOccurrenceOwnership()).contains(occurrence);
        assertThatThrownBy(() -> record.captureScoringOccurrence(ScoringOccurrenceOwnership.authoritative(
                "event-b",
                Instant.parse("2026-10-05T08:01:00Z"),
                "b".repeat(64)
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessage("FRAUD_FEEDBACK_SCORING_OCCURRENCE_IMMUTABLE");
    }

    @Test
    void historicalFeedbackWithoutOccurrenceIdentityHasExplicitlyUnavailableLineage() {
        assertThat(new FraudFeedbackRecord().scoringOccurrenceOwnership()).isEmpty();
    }

    @Test
    void publicFeedbackResponseDoesNotExposePrivateOccurrenceIdentity() {
        assertThat(Arrays.stream(FraudFeedbackResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("sourceEventId", "sourceEventCreatedAt", "sourceEventFingerprint");
    }

    private static FraudFeedbackRecord recordWithCorruptedComparisonSnapshot(EngineIntelligenceResponseStatus status) {
        FraudFeedbackRecord record = new FraudFeedbackRecord();
        record.setEngineIntelligenceStatus(status);
        record.setComparisonType(EngineIntelligenceComparisonType.RULES_VS_ML);
        record.setComparedEngineIds(List.of("rules.primary"));
        record.setAgreementStatus(EngineIntelligenceAgreementStatus.AGREEMENT);
        record.setRiskMismatchStatus(EngineIntelligenceRiskMismatchStatus.SAME_RISK_LEVEL);
        record.setScoreDeltaBucket(EngineIntelligenceScoreDeltaBucket.NONE);
        return record;
    }
}
