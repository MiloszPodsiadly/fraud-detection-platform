package com.frauddetection.scoring.domain;

import com.frauddetection.common.events.evidence.ScoringEvidenceItem;
import com.frauddetection.common.events.enums.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FraudScoreResult(
        Double fraudScore,
        RiskLevel riskLevel,
        String scoringStrategy,
        String modelName,
        String modelVersion,
        Instant inferenceTimestamp,
        List<String> reasonCodes,
        Map<String, Object> scoreDetails,
        Map<String, Object> featureSnapshot,
        Map<String, Object> explanationMetadata,
        Boolean alertRecommended,
        List<ScoringEvidenceItem> scoringEvidence
) {
    public FraudScoreResult {
        scoringEvidence = scoringEvidence == null ? List.of() : List.copyOf(scoringEvidence);
    }

    public FraudScoreResult(
            Double fraudScore,
            RiskLevel riskLevel,
            String scoringStrategy,
            String modelName,
            String modelVersion,
            Instant inferenceTimestamp,
            List<String> reasonCodes,
            Map<String, Object> scoreDetails,
            Map<String, Object> featureSnapshot,
            Map<String, Object> explanationMetadata,
            Boolean alertRecommended
    ) {
        this(
                fraudScore,
                riskLevel,
                scoringStrategy,
                modelName,
                modelVersion,
                inferenceTimestamp,
                reasonCodes,
                scoreDetails,
                featureSnapshot,
                explanationMetadata,
                alertRecommended,
                List.of()
        );
    }
}
