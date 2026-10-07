package com.frauddetection.scoring.orchestration.aggregation;

import com.frauddetection.common.events.intelligence.MlPredictionEvidenceOmissionReason;

public enum EngineIntelligenceEmissionOmissionReason {
    DISABLED,
    PIPELINE_UNAVAILABLE,
    EMPTY_RESULT,
    UNKNOWN_FAILURE;

    public MlPredictionEvidenceOmissionReason toMlPredictionEvidenceOmissionReason() {
        return switch (this) {
            case DISABLED -> MlPredictionEvidenceOmissionReason.DIAGNOSTIC_EMISSION_DISABLED;
            case EMPTY_RESULT -> MlPredictionEvidenceOmissionReason.EVIDENCE_SOURCE_INTEGRITY_FAILURE;
            case PIPELINE_UNAVAILABLE, UNKNOWN_FAILURE ->
                    MlPredictionEvidenceOmissionReason.DIAGNOSTIC_ENRICHMENT_UNAVAILABLE;
        };
    }
}
