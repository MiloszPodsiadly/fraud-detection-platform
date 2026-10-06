package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.EngineIntelligenceSummary;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;
import com.frauddetection.common.events.intelligence.MlPredictionEvidenceV1;

import java.util.Objects;
import java.util.Optional;

public record EngineIntelligenceEnrichmentResult(
        Optional<EngineIntelligenceSummary> engineIntelligenceSummary,
        Optional<MlPredictionEvidenceV1> mlPredictionEvidence,
        Optional<MlPredictionEvidenceOmissionReason> mlPredictionEvidenceOmissionReason
) {
    public EngineIntelligenceEnrichmentResult {
        engineIntelligenceSummary = Objects.requireNonNull(
                engineIntelligenceSummary,
                "engineIntelligenceSummary is required"
        );
        mlPredictionEvidence = Objects.requireNonNull(mlPredictionEvidence, "mlPredictionEvidence is required");
        mlPredictionEvidenceOmissionReason = Objects.requireNonNull(
                mlPredictionEvidenceOmissionReason,
                "mlPredictionEvidenceOmissionReason is required"
        );
        if (engineIntelligenceSummary.isEmpty() && mlPredictionEvidence.isPresent()) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_ENGINE_INTELLIGENCE_SUMMARY");
        }
        if (mlPredictionEvidence.isPresent() == mlPredictionEvidenceOmissionReason.isPresent()) {
            throw new IllegalArgumentException("ML_PREDICTION_EVIDENCE_REQUIRES_EXACTLY_ONE_OUTCOME");
        }
    }

    public static EngineIntelligenceEnrichmentResult withEvidence(
            EngineIntelligenceSummary engineIntelligenceSummary,
            MlPredictionEvidenceV1 mlPredictionEvidence
    ) {
        return new EngineIntelligenceEnrichmentResult(
                Optional.of(Objects.requireNonNull(engineIntelligenceSummary, "engineIntelligenceSummary is required")),
                Optional.of(Objects.requireNonNull(mlPredictionEvidence, "mlPredictionEvidence is required")),
                Optional.empty()
        );
    }

    public static EngineIntelligenceEnrichmentResult withoutEvidence(
            EngineIntelligenceSummary engineIntelligenceSummary,
            MlPredictionEvidenceOmissionReason omissionReason
    ) {
        return new EngineIntelligenceEnrichmentResult(
                Optional.of(Objects.requireNonNull(engineIntelligenceSummary, "engineIntelligenceSummary is required")),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(omissionReason, "omissionReason is required"))
        );
    }
}
