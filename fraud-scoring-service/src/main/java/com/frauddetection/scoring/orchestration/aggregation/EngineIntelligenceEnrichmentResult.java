package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidence;

import java.util.Objects;
import java.util.Optional;

public record EngineIntelligenceEnrichmentResult(
        EngineIntelligenceSummary engineIntelligenceSummary,
        Optional<MlPredictionEvidence> mlPredictionEvidence,
        Optional<MlPredictionEvidenceOmissionReason> mlPredictionEvidenceOmissionReason
) {
    public EngineIntelligenceEnrichmentResult {
        engineIntelligenceSummary = Objects.requireNonNull(engineIntelligenceSummary, "engineIntelligenceSummary is required");
        mlPredictionEvidence = Objects.requireNonNull(mlPredictionEvidence, "mlPredictionEvidence is required");
        mlPredictionEvidenceOmissionReason = Objects.requireNonNull(
                mlPredictionEvidenceOmissionReason,
                "mlPredictionEvidenceOmissionReason is required"
        );
        if (mlPredictionEvidence.isPresent() == mlPredictionEvidenceOmissionReason.isPresent()) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
        }
    }

    public static EngineIntelligenceEnrichmentResult withEvidence(
            EngineIntelligenceSummary engineIntelligenceSummary,
            MlPredictionEvidence mlPredictionEvidence
    ) {
        return new EngineIntelligenceEnrichmentResult(
                Objects.requireNonNull(engineIntelligenceSummary, "engineIntelligenceSummary is required"),
                Optional.of(Objects.requireNonNull(mlPredictionEvidence, "mlPredictionEvidence is required")),
                Optional.empty()
        );
    }

    public static EngineIntelligenceEnrichmentResult withoutEvidence(
            EngineIntelligenceSummary engineIntelligenceSummary,
            MlPredictionEvidenceOmissionReason omissionReason
    ) {
        return new EngineIntelligenceEnrichmentResult(
                Objects.requireNonNull(engineIntelligenceSummary, "engineIntelligenceSummary is required"),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(omissionReason, "omissionReason is required"))
        );
    }
}
