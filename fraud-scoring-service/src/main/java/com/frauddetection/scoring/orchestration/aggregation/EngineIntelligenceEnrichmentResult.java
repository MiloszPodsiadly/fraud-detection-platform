package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;

import java.util.Objects;
import java.util.Optional;

public record EngineIntelligenceEnrichmentResult(
        Optional<EngineIntelligenceSummary> engineIntelligenceSummary,
        Optional<MlPredictionEvidenceV1> mlPredictionEvidence
) {
    public EngineIntelligenceEnrichmentResult {
        engineIntelligenceSummary = Objects.requireNonNull(
                engineIntelligenceSummary,
                "engineIntelligenceSummary is required"
        );
        mlPredictionEvidence = Objects.requireNonNull(mlPredictionEvidence, "mlPredictionEvidence is required");
        if (engineIntelligenceSummary.isEmpty() && mlPredictionEvidence.isPresent()) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_ENGINE_INTELLIGENCE_SUMMARY");
        }
    }

    public static EngineIntelligenceEnrichmentResult of(
            EngineIntelligenceSummary engineIntelligenceSummary,
            Optional<MlPredictionEvidenceV1> mlPredictionEvidence
    ) {
        return new EngineIntelligenceEnrichmentResult(
                Optional.of(Objects.requireNonNull(engineIntelligenceSummary, "engineIntelligenceSummary is required")),
                mlPredictionEvidence
        );
    }

}
