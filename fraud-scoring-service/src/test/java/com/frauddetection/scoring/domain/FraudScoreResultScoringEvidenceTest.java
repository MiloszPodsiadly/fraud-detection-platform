package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.evidence.ScoringEvidenceSeverity;
import com.frauddetection.common.events.evidence.ScoringEvidenceSource;
import com.frauddetection.common.events.evidence.ScoringEvidenceStatus;
import com.frauddetection.common.events.evidence.ScoringEvidenceType;
import com.frauddetection.common.events.enums.RiskLevel;
import com.frauddetection.common.events.reason.ReasonCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudScoreResultScoringEvidenceTest {

    @Test
    void existingFieldsStillWorkAndScoringEvidenceDefaultsToEmpty() {
        FraudScoreResult result = new FraudScoreResult(
                0.91d,
                RiskLevel.CRITICAL,
                "RULE_BASED",
                "rule-based-engine",
                "v1",
                Instant.now(),
                List.of(ReasonCode.COUNTRY_MISMATCH.wireValue()),
                Map.of("finalScore", 0.91d),
                Map.of("featureFlagCount", 1),
                Map.of("explanationType", "WEIGHTED_REASON_CODES"),
                true,
                null
        );

        assertThat(result.reasonCodes()).containsExactly(ReasonCode.COUNTRY_MISMATCH.wireValue());
        assertThat(result.scoreDetails()).containsEntry("finalScore", 0.91d);
        assertThat(result.featureSnapshot()).containsEntry("featureFlagCount", 1);
        assertThat(result.explanationMetadata()).containsEntry("explanationType", "WEIGHTED_REASON_CODES");
        assertThat(result.scoringEvidence()).isEmpty();
    }

    @Test
    void scoringEvidenceIsAdditiveImmutableAndDoesNotReplaceReasonCodes() {
        List<ScoringEvidenceItem> scoringEvidence = new ArrayList<>();
        scoringEvidence.add(evidence());

        FraudScoreResult result = new FraudScoreResult(
                0.82d,
                RiskLevel.HIGH,
                "ML",
                "python-logistic-fraud-model",
                "test-version",
                Instant.now(),
                List.of(ReasonCode.MODEL_HIGH_RISK.wireValue()),
                Map.of("modelAvailable", true),
                Map.of(),
                Map.of("modelAvailable", true),
                true,
                scoringEvidence
        );
        scoringEvidence.clear();

        assertThat(result.reasonCodes()).containsExactly(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThat(result.scoringEvidence()).hasSize(1);
        assertThat(result.scoringEvidence().getFirst().reasonCode()).isEqualTo(ReasonCode.MODEL_HIGH_RISK.wireValue());
        assertThatThrownBy(() -> result.scoringEvidence().add(evidence()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ScoringEvidenceItem evidence() {
        return new ScoringEvidenceItem(
                "ML_MODEL:MODEL_HIGH_RISK:0",
                ReasonCode.MODEL_HIGH_RISK.wireValue(),
                ScoringEvidenceType.MODEL_EXPLANATION,
                ScoringEvidenceSource.ML_MODEL,
                ScoringEvidenceStatus.AVAILABLE,
                ScoringEvidenceSeverity.HIGH,
                ReasonCode.MODEL_HIGH_RISK.title(),
                ReasonCode.MODEL_HIGH_RISK.description(),
                null,
                null,
                Map.of("parseStatus", "KNOWN"),
                Instant.now()
        );
    }
}
